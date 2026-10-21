# Muse Gadget protocol: spec for a Kotlin/Android TV port

Source analysed: `muse-gadget-sdk/linux` (package `musegadget` 0.1.0, Apache-2.0,
© Meta Platforms). Line references are to files under `linux/src/musegadget/`
unless stated otherwise. The ESP32 firmware in `../esp32` was only consulted to
settle advertising-layout questions (§2.2); the Linux SDK is the source of truth
everywhere else.

Companion file generated from the SDK code for this spec:
`scratchpad/muse_extra_vectors.json` (made by `scratchpad/gen_vectors.py`). It
holds deterministic vectors for BLE framing, a device→phone pairing record, the
protobuf envelopes, the Noise frame, and a full Noise XX handshake with fixed
keys. See §6.

> **Naming.** The server and app still use "hatch" in some identifiers. Keep them
> byte-for-byte: host `hatch.metaaivm.com`, auth prefix `hatch_refresh:`, labels
> `hatch-link-pairing-v5`, `hatch-link ble setup v1`, `hatch-link session id v1`,
> model `hatch_link`, and device-id prefix `hatch-link:`. Upstream's AGENTS.md says
> user-visible text should say "Muse", never "Hatch".

---

## 0. Overview of the whole flow

```
          ┌──────────── PAIRING (BLE, once, ≤10 min window) ───────────┐
Phone ──BLE GATT write RX──► Device: {"action":"get_device_info"}      (plaintext)
Device ──notify TX──► Phone:       device_info                           (plaintext JSON, chunk-framed)
Phone ─► Device: pairing_client_hello (P-256 pub + nonce)               (plaintext)
Device ─► Phone: pairing_ready (P-256 pub + nonce + transcript_hash + session_id)
   ── both derive AES-256-GCM keys (ECDH P-256 → HKDF-SHA256) ──
Phone ─► Device: pairing_encrypted{ {"action":"pairing_client_finished"} }   counter 0
Device ─► Phone: pairing_encrypted{ status pairing_confirmed + sdk_token }   counter 0
Phone ─► Device: pairing_encrypted{ {"action":"wifi_scan"} }
Device ─► Phone: pairing_encrypted{ wifi_scan_result (one "current connection" entry) }
Phone ─► Device: pairing_encrypted{ provision_v2 (Wi-Fi + device access/refresh tokens + URLs) }
Device ─► Phone: status wifi_connecting, wifi_connected
Device ──HTTPS GET /fetch_vms (Bearer access_token)──► api.muse.ai   (token check)
Device ─► Phone: status auth_ok   → persist pairing, stop BLE after 1.5 s
          └────────────────────────────────────────────────────────────┘

          ┌──────────── RUNTIME (forever, reconnect loop) ─────────────┐
Device ──HTTPS POST /device_token/refresh (every 3 h, Bearer hatch_refresh:<rt>)
Device ──HTTPS GET /fetch_vms──► per-VM {vm_id, vm_auth_token}
Device ──WSS wss://hatch.metaaivm.com/v1/noise?vm_id=… (Authorization: Bearer vm_auth_token)
   Noise_XX_25519_AESGCM_SHA256 handshake (3 binary WS messages)
   Every later WS binary message = AES-GCM(NoiseTransportFrame(protobuf))
   Inside: HTTP-like multiplexed streams (ServiceRequest/ServiceFrame protobufs)
     stream 1: POST /link-control, long-lived, body = u32le-length-prefixed JSON
        device → VM: link.register, link.result
        VM → device: register reply, link.invoke, link.unpaired
     stream n: POST /chat/stream      (send a user message; JSON ack)
     stream n: POST /chat/subscribe   (NDJSON stream of chat events)
     stream n: GET  /api/voice/tts-stream?message_id=…  (MP3)
          └────────────────────────────────────────────────────────────┘
```

Note: the BLE pairing does **not** use Noise. It uses its own P-256 ECDH +
HKDF-SHA256 + AES-256-GCM record layer. Noise XX is used only for the
cloud link.

---

## 1. Device identity (`identity.py`, `config.py`)

### 1.1 Constants (verbatim)

```python
NODE_ID_PREFIX = "homelink-"
BLE_NAME_PREFIX = "MuseGadget"
_MAC_RE = re.compile(r"[0-9a-f]{2}(:[0-9a-f]{2}){5}")
```

### 1.2 The identity is a random MAC-shaped value, not a key pair

There are **no long-term device keys**. The only persistent identity is a random
"MAC" made once and kept across unpairing:

```python
def generate_mac(random_bytes=os.urandom) -> str:
    octets = bytearray(random_bytes(6))
    octets[0] = (octets[0] & 0xFC) | 0x02  # unicast, locally administered
    return ":".join(f"{b:02x}" for b in octets)
```

* Format: lowercase hex, colon-separated, e.g. `02:00:00:ab:cd:ef`. Must match
  `_MAC_RE`. If the stored value is missing or invalid, a new one is generated.
* It is not read from any interface, so it never exposes the real BT or Wi-Fi MAC.

Derived names:

| Property | Formula | Example (`02:00:00:ab:cd:ef`) |
|---|---|---|
| `suffix` | `mac.replace(":", "")[-6:]` (lowercase) | `abcdef` |
| `node_id` | `"homelink-" + suffix` | `homelink-abcdef` |
| `device_id` | `"hatch-link:" + mac` | `hatch-link:02:00:00:ab:cd:ef` |
| `ble_name` | `"MuseGadget" + suffix.upper()` (**no hyphen**) | `MuseGadgetABCDEF` |

From `tests/test_identity.py`:
```python
ident = identity.Identity("02:00:00:ab:cd:ef")
assert ident.node_id == "homelink-abcdef"
assert ident.device_id == "hatch-link:02:00:00:ab:cd:ef"
assert ident.ble_name == "MuseGadgetABCDEF"
# generate_mac(lambda n: bytes(range(n))) == "02:01:02:03:04:05"
```

Where each one is used:
* `device_id` (`hatch-link:<mac>`) appears only in BLE pairing: `device_info`, `pairing_ready`,
  and the transcript.
* `node_id` is used in the BLE `device_info`, `pairing_ready` and transcript. On the cloud side it
  is the `device_id` sent to `/device_token/refresh`, the `node_id` in `link.register`, and the
  `device_id` in `/chat/stream`.
* `mac` is used in BLE `device_info`, `pairing_ready` and the transcript.
* `ble_name` is the advertised and GAP name.

### 1.3 Persistent state (Linux: `/var/lib/musegadget`, mode 0700; files 0600, atomic write)

| File | Content | Survives unpair? |
|---|---|---|
| `identity.json` | `{"mac": "02:…"}` | yes |
| `pairing.json` | see below | no (deleted on unpair / revoke) |
| `sdk_token` | the `mgst_…` string (text) | yes |

`pairing.json` as written by `pair._verify_and_save`:
```python
record = {
    "access_token": credentials.access_token,
    "refresh_token": credentials.refresh_token,
    "token_type": "device",
    "username": credentials.username,
    "api_url": api_url,          # only kept if it starts with "https://", else ""
    "api_url_v2": api_url_v2,    # only kept if it starts with "https://", else ""
    "noise_host": credentials.noise_host,
    "access_token_saved_at": int(time.time()),
    "sdk_token_hint": config.sdk_token_hint(sdk_token),   # token[:12] or "none"
}
```
After a token rotation, `access_token`, `refresh_token` and `access_token_saved_at`
are replaced.

Android mapping: store all of this in app-private storage (`Context.getFilesDir()`
or `noBackupFilesDir`). You can wrap the tokens with an Android Keystore AES key.
Write atomically (write a temp file, then rename).

### 1.4 SDK token (`config.py`)

```python
# mgst_ plus 43 canonical base64url characters, as issued by gadgets.muse.ai.
_SDK_TOKEN = re.compile(r"mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
```
* Total length 48. The last character is limited to the base64url chars whose low 2 bits
  are zero, because 43 chars encode exactly 32 bytes.
* Get it from https://gadgets.muse.ai/settings/sdk-tokens. Terms: https://gadgets.muse.ai/sdk-terms.
* `sdk_token_hint(token) = token[:12]` or `"none"`.
* Where it goes:
  1. To the **phone** in the encrypted `pairing_confirmed` status (§2.6). The server only records
     the SDK token at pairing time.
  2. To the **API** in the body of `/device_token/refresh` (§3.2).
* Without a token, pairing still works today. The CLI warns: "gadgets without one will stop
  pairing". Treat the token as required.
* If the token changes after pairing, upstream says you must re-pair (`pair --force`) for the
  new token to take effect.

---

## 2. BLE pairing

### 2.1 GATT layout (`ble_server.py`)

```python
SERVICE_UUID = "7fdd3d1c-38ea-46cf-8b46-314ecf5f240c"
RX_UUID      = "4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01"   # phone writes commands
TX_UUID      = "d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c"   # device notifies responses
PAIRED_FLAG_COMPANY_ID = 0xFFFF
```

| Characteristic | UUID | BlueZ flags | Android equivalent |
|---|---|---|---|
| RX | `4d593029-…9b01` | `["write", "write-without-response"]` | `PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE`, `PERMISSION_WRITE` |
| TX | `d75dc4ca-…5b6c` | `["read", "notify"]` | `PROPERTY_READ or PROPERTY_NOTIFY`, `PERMISSION_READ`, plus a CCCD descriptor `00002902-0000-1000-8000-00805f9b34fb` (READ\|WRITE). BlueZ adds the CCCD for you; on Android you must add it. |

* One primary service. No other descriptors.
* Reading TX returns the last notified value.
* No link-layer bonding or encryption: BlueZ sets the adapter `Pairable=False`. Do not require
  encrypted GATT permissions.
* A write arrives with `options["mtu"]`. The server tracks the negotiated ATT MTU per connection.
  On disconnect it resets to the assumed MTU and clears the pairing session (§2.8).

### 2.2 Advertising

Linux (`Advertisement.properties`):
```python
"Type": "peripheral",
"LocalName": <ble_name>,                          # "MuseGadgetXXXXXX"
"ServiceUUIDs": [SERVICE_UUID],
"ManufacturerData": {0xFFFF: [0x00]},             # company 0xFFFF, 1 byte "paired flag"
"Includes": [],
```
The adapter's **GAP name is also changed** for the duration, then restored:
```python
# The phone reads the GAP name as well as the advertised one.
adapter.Set(ADAPTER_IFACE, "Alias", dbus.String(self._local_name))
adapter.Set(ADAPTER_IFACE, "Pairable", dbus.Boolean(False))
```
The ESP32 reference shows the exact packet layout (`esp32/main/ble_server.c`):
* **Adv packet** (legacy, connectable, general discoverable): Flags
  (`LE General Discoverable | BR/EDR not supported`), the **complete list of 128-bit service
  UUIDs** = `SERVICE_UUID`, and **manufacturer data** `FF FF <flag>`. The flag is `0x00` while
  unpaired and `0x01` once set up. About 26 bytes.
* **Scan response**: **Complete Local Name** = the BLE name. It is put there "so the UUID + name
  both fit within the 31-byte legacy advertising limit".
* Linux always advertises flag `0x00`, because it only advertises while `pair` runs. Upstream:
  "the value is informational, not authenticated".

**Android implementation (important):**
* `AdvertiseData` cannot carry an arbitrary local name. `setIncludeDeviceName(true)` uses
  `BluetoothAdapter.getName()`. You must call `BluetoothAdapter.setName("MuseGadgetXXXXXX")`
  for the pairing window and restore the old name afterwards. On API ≤30 this needs
  `BLUETOOTH_ADMIN`. This also gives you the GAP name the phone reads. Side effect: the TV shows
  up under that name to every BT device while pairing is open.
* Advertise data: `addServiceUuid(ParcelUuid(SERVICE_UUID))` and
  `addManufacturerData(0xFFFF, byteArrayOf(0x00))`, with `setIncludeDeviceName(false)` and
  `setIncludeTxPowerLevel(false)`. Android adds Flags (3 bytes) for connectable ads. Total:
  3 + 18 + 5 = 26 bytes, which is within 31.
* Scan response: `setIncludeDeviceName(true)`. That is 16 + 2 = 18 bytes.
* Use legacy advertising (`AdvertiseSettings`, `setConnectable(true)`). Extended advertising
  isn't needed, and phone scanners filter legacy ads most reliably.
* Advertise only while the 10-minute pairing window is open (§2.9).

**Name format conflict (open question, §8):** the Linux SDK says "no hyphen"
(`MuseGadgetABCDEF`). The ESP32 firmware uses `"MuseGadget-%02X%02X%02X"`. Linux AGENTS.md:
"the apps compare the text after the prefix with the text after `homelink-` in the node id".
Follow Linux (no hyphen), since that is the community-device path we copy.

### 2.3 MTU and framing (`ble_framing.py`)

```python
CHUNK_MAGIC = 0xFE
HEADER_BYTES = 3
MAX_PACKET_BYTES = 160
MAX_CHUNKS = 255
MAX_MESSAGE_BYTES = 8192
DEFAULT_ATT_MTU = 23
CHUNK_STAGGER_S = 0.05
_ASSUMED_MTU = MAX_PACKET_BYTES + 3   # ble_server.py: 163 until a write reports the real MTU
```

**Frame layout (both directions):**

```
byte 0     0xFE                      (magic)
byte 1     chunk index (0-based, u8)
byte 2     total chunks (u8, 1..255)
byte 3..   payload fragment
```

A write or notification that does **not** start with `0xFE` is a complete, unchunked
message. A packet shorter than 3 bytes also counts as unchunked.

Encoding (the device's notifications):
```python
def encode_chunks(data: bytes, mtu: int = DEFAULT_ATT_MTU) -> list[bytes]:
    notify_max = min(mtu - 3 if mtu > 3 else 20, MAX_PACKET_BYTES)
    usable = notify_max - HEADER_BYTES
    fragments = [data[i:i + usable] for i in range(0, len(data), usable)] or [b""]
    if len(fragments) > MAX_CHUNKS:
        raise ValueError(...)
    total = len(fragments)
    return [bytes([CHUNK_MAGIC, i, total]) + frag for i, frag in enumerate(fragments)]
```
* Payload per packet = `min(mtu-3, 160) - 3`. That is 17 at MTU 23 and 157 at MTU ≥ 163.
* **Every JSON message the device sends is chunk-framed, even if it fits in one packet.** The
  one exception is the plaintext error statuses (§2.5). Those are sent as raw ASCII with no
  header and **no JSON**.
* Packets are notified in order, with `CHUNK_STAGGER_S` (50 ms) between them. On Android you
  must also wait for `onNotificationSent` before calling the next
  `notifyCharacteristicChanged(device, tx, false)`.

Reassembly (the phone's writes), `ChunkAssembler.feed`:
```python
if len(packet) < HEADER_BYTES or packet[0] != CHUNK_MAGIC:
    return bytes(packet)                       # complete unchunked message
index, total, fragment = packet[1], packet[2], packet[3:]
if total == 0: reset(); return None
if index == 0 or total != self._total: reset(); self._total = total
if index != self._next or index >= self._total: reset(); return None   # strictly in order
if len(buf) + len(fragment) > max_bytes (8192): reset(); return None
buf += fragment; self._next = index + 1
if self._next < self._total: return None
return message (and reset)
```

MTU notes (upstream AGENTS.md):
* "The Android app writes `negotiatedMtu - 3` bytes with no cap, and Android rejects writes
  over 512 bytes." BlueZ needed `ExchangeMTU = 256`.
* On an Android GATT server, take the MTU from `onMtuChanged(device, mtu)`. Default to 23
  (Python assumes 163 until told otherwise). Writes up to MTU-3 arrive in
  `onCharacteristicWriteRequest`. If `preparedWrite == true` (long write), buffer by offset and
  deliver on `onExecuteWrite(execute=true)`. Always `sendResponse(GATT_SUCCESS)` when
  `responseNeeded`.
* Reply notifications are capped at 160 bytes no matter how large the MTU is.

Test vector (`muse_extra_vectors.json › ble_framing`):
`{"action":"get_device_info"}` at MTU 23 →
`fe00027b22616374696f6e223a226765745f6465`, `fe0102766963655f696e666f227d`.

### 2.4 Setup command dispatcher (`ble_setup.py › SetupController.handle_message`)

Every reassembled message is UTF-8 JSON with an `"action"` string. Messages are handled one at
a time, in order, on a worker thread. All sends happen under one lock, so encrypted counters
stay in order.

```python
SENSITIVE_ACTIONS = frozenset({
    "provision", "provision_v2", "wifi_scan", "ota", "device.ota",
    "unpair", "set_wifi", "set_auth",
})
PLAINTEXT_STATUSES = frozenset({
    "error_encryption_required",
    "error_pairing_invalid_hello",
    "error_pairing_unavailable",
    "error_pairing_decrypt",
})
DISCONNECT_AFTER_ERROR_S = 0.3
```

Dispatch, in exact order:
1. If the JSON is invalid or not an object, send status `error_invalid_command`.
2. If plaintext and `action == "pairing_client_hello"`, run `_handle_hello` (§2.6).
3. If plaintext and `action == "pairing_encrypted"`, decrypt the record and dispatch the
   plaintext again with `decrypted=True`.
4. If `action == "get_device_info"` (plaintext **or** encrypted, at any time), send the
   `device_info` JSON. Android re-sends it when it restarts a handshake on the same connection.
5. If plaintext and a hello has already been accepted on this connection (`plaintext_blocked`),
   **silently ignore** the message (log only).
6. If plaintext and the action is in `SENSITIVE_ACTIONS`, send status
   `error_encryption_required`.
7. If decrypted and `action == "pairing_client_finished"`, run `_handle_client_finished`.
8. If decrypted, the action is in `SENSITIVE_ACTIONS`, and the session is not confirmed, send
   status `error_pairing_confirm_required`.
9. If decrypted and `action == "wifi_scan"`, send `wifi_scan_result`.
10. If decrypted and `action == "provision_v2"`, run `_handle_provision`.
11. Otherwise send status `error_unknown_action`.

Note the asymmetry: the **phone** sends `{"action":"pairing_encrypted", …}`, and the **device**
sends `{"type":"pairing_encrypted", …}`.

### 2.5 Device → phone message shapes

All JSON is serialised compactly (`json.dumps(obj, separators=(",", ":"))`).

**device_info** (always plaintext). Key order as emitted:
```json
{"type":"device_info",
 "node_id":"homelink-abcdef",
 "version":"0.1.0",
 "device_id":"hatch-link:02:00:00:ab:cd:ef",
 "mac":"02:00:00:ab:cd:ef",
 "model":"hatch_link",
 "pairing_protocol":5,
 "pairing_auth":"none",
 "pairing_auth_epoch":0,
 "pairing_policy":"confirm_app",
 "build_sha":"",
 "network_ready":true}
```
* `network_ready` = `network.is_online()`, which is a TCP connect to `api.muse.ai:443` with a 5 s
  timeout.
* AGENTS.md: "`get_device_info` must report `model: "hatch_link"`."

**status** (`send_status(status, generation)`):
* If a session has keys, send it encrypted. The inner plaintext is
  `{"type":"status","status":"<status>"}`. For `pairing_confirmed` it also carries
  `"sdk_token":"mgst_…"` when a token is configured.
* If there are no keys, the status is sent as **raw ASCII bytes, unframed and not JSON**
  (e.g. the bytes `error_encryption_required`). This only happens when `generation == 0`, no
  hello has been accepted on this connection, and the status is in `PLAINTEXT_STATUSES`.
  Otherwise it is dropped.

Status strings the device can emit:

| Status | When |
|---|---|
| `error_invalid_command` | not JSON / not an object |
| `error_unknown_action` | unknown action |
| `error_encryption_required` | sensitive action sent in plaintext (before any hello) |
| `error_pairing_invalid_hello` | bad hello (plaintext) |
| `error_pairing_unavailable` | listed in `PLAINTEXT_STATUSES`, never raised by the Linux code |
| `error_pairing_decrypt` | record failed to decrypt, or bad client_finished. Then disconnect after 0.3 s. If the session has already been reset, this status is usually suppressed (no keys, plaintext blocked). |
| `error_pairing_confirm_required` | sensitive action before confirmation, or `provision_v2` when `mark_provisioning()` fails |
| `pairing_confirmed` | after a valid client_finished (+ `sdk_token`) |
| `error_missing_credentials` | `provision_v2` failed validation |
| `error_operation_in_progress` | `provision_v2` while another is running |
| `wifi_connecting` | provisioning started |
| `wifi_connected` | device online |
| `wifi_failed` | device offline. Provisioning stays open (deadline extended) so the app can retry. |
| `auth_ok` | tokens verified (fetch_vms returned ≥1 VM) and saved. Setup is complete. |
| `auth_failed` | fetch_vms returned no VMs or an error. Disconnect after 0.5 s. |
| `error_storage` | the commit (persist) failed or the session went stale. Disconnect after 0.5 s. |

**wifi_scan_result** (always encrypted):
```json
{"type":"wifi_scan_result","networks":[{"ssid":"<active SSID or 'Use current connection'>","rssi":-40,"secure":false}]}
```
If the device is offline, `networks` is `[]`. `CURRENT_CONNECTION_LABEL = "Use current
connection"` (`network.py`). The entry is marked open so the app skips the password field.
Using the real SSID lets the app preselect it. On Android, getting the SSID needs location
permission (Android 10+). Use the label for Ethernet or when the SSID is unknown.

### 2.6 Pairing protocol v5, community mode (`pairing.py`)

Constants (verbatim):
```python
PAIRING_VERSION = 5
PAIRING_MODEL = "hatch_link"
PAIRING_SUITE = "p256-hkdf-sha256-aes-gcm-v1"
POLICY_BUTTON = "confirm_press"
POLICY_APP = "confirm_app"
AUTH_OFFICIAL = "fleet_ecdsa_p256_v1"
AUTH_COMMUNITY = "none"
BUTTON_CONFIRM_TIMEOUT_S = 60
RECORD_LABEL = "hatch-link ble setup v1"
SESSION_ID_LABEL = b"hatch-link session id v1"
CLIENT_FINISHED_TIMEOUT_S = 60
CONFIRMED_TIMEOUT_S = 120
PROVISIONING_TIMEOUT_S = 120
ERROR_INVALID_HELLO = "error_pairing_invalid_hello"
ERROR_DECRYPT = "error_pairing_decrypt"
_P256_POINT_BYTES = 65
_NONCE_BYTES = 16
_SESSION_ID_BYTES = 16
_TAG_BYTES = 16
_MAX_B64_CHARS = 4096
_MAX_CIPHERTEXT_B64_CHARS = 16384
_TO_DEVICE = 0
_FROM_DEVICE = 1
```

The device implements community mode only (`pairing_auth: "none"`, epoch 0, policy
`confirm_app`). The official mode (`fleet_ecdsa_p256_v1`, which needs a manufacturer ECDSA
`device_proof`) is not implemented and is not needed.

#### Step A: `pairing_client_hello` (phone → device, plaintext)

```json
{"action":"pairing_client_hello","version":5,"pairing_auth":"none","pairing_policy":"confirm_app",
 "mobile_pub":"<b64url, 65-byte uncompressed P-256 point 0x04||X||Y>",
 "mobile_nonce":"<b64url, 16 bytes>"}
```
The hello is valid only if all of these hold:
* `version` is a JSON **number** equal to 5. `true` and `"5"` are rejected.
* `pairing_auth == "none"` and `pairing_policy == "confirm_app"`.
* `mobile_pub` decodes to exactly 65 bytes, starting with `0x04`, and is a valid curve point.
* `mobile_nonce` decodes to exactly 16 bytes.

On failure, the device sends plaintext status `error_pairing_invalid_hello` and stays IDLE.

On success, any previous session is reset. Then:
1. Generate a fresh ephemeral P-256 key pair. `device_pub` = 65-byte uncompressed X9.62 point.
2. Generate `device_nonce` = 16 random bytes.
3. Build the transcript (exact text, `\n`-joined, **no trailing newline**). All key and nonce
   values are unpadded base64url:
```python
"\n".join([
    "hatch-link-pairing-v5",
    "version=5",
    "initiator_role=mobile",
    "responder_role=link",
    f"device_id={device_id}",                 # hatch-link:<mac>
    f"node_id={node_id}",                     # homelink-xxxxxx
    f"mac={mac}",
    "model=hatch_link",
    f"firmware_version={firmware_version}",   # same string as device_info.version ("0.1.0"; "unknown" if empty)
    "selected_cipher_suite=p256-hkdf-sha256-aes-gcm-v1",
    "pairing_auth=none",
    "pairing_auth_epoch=0",
    "pairing_policy=confirm_app",
    "confirm_timeout_seconds=0",              # 60 only for confirm_press
    f"mobile_pub={mobile_pub_b64}",
    f"device_pub={device_pub_b64}",
    f"mobile_nonce={mobile_nonce_b64}",
    f"device_nonce={device_nonce_b64}",
])
```
4. `transcript_hash = SHA-256(transcript UTF-8)`.
5. `ecdh_secret` = P-256 ECDH shared secret: the 32-byte big-endian X coordinate.
6. Key schedule (`derive_session_keys`):
```
salt           = SHA-256(mobile_nonce_raw || device_nonce_raw || transcript_hash)
session_secret = HKDF-SHA256(IKM=ecdh_secret, salt=salt, info="hatch-link ble setup v1", L=32)   # extract+expand
mobile_tx_key  = HKDF-Expand-SHA256(PRK=session_secret, info="mobile->device", L=32)             # expand only
mobile_rx_key  = HKDF-Expand-SHA256(PRK=session_secret, info="device->mobile", L=32)             # expand only
session_id     = SHA-256("hatch-link session id v1" || transcript_hash || ecdh_secret)[0:16]
```
   The device **decrypts** with `mobile_tx_key` and **encrypts** with `mobile_rx_key`.
   `session_id_b64` = unpadded base64url of the 16 bytes.
7. State becomes `WAIT_CLIENT_FINISHED`, with a deadline of now + 60 s. Both counters are 0.
8. Reply (chunk-framed plaintext JSON):
```json
{"type":"pairing_ready","version":5,"device_id":"hatch-link:…","node_id":"homelink-…","mac":"…",
 "model":"hatch_link","firmware_version":"0.1.0","pairing_auth":"none","pairing_auth_epoch":0,
 "pairing_policy":"confirm_app","device_pub":"<b64url>","device_nonce":"<b64url>",
 "transcript_hash":"<b64url>","session_id":"<b64url>"}
```
   This is about 600 bytes, so at MTU 23 it takes about 36 notifications.

#### Record layer (`pairing_encrypted`)

```
nonce (12 B) = [direction, 0, 0, 0] || counter as u64 big-endian      # direction 0 = to device (m2d), 1 = from device (d2m)
AAD          = UTF-8 "hatch-link ble setup v1|<session_id_b64>|<m2d|d2m>|<counter decimal>"
AES-256-GCM, 16-byte tag
```
Envelope:
```json
// phone → device
{"action":"pairing_encrypted","session_id":"<b64url>","counter":"0","ciphertext":"<b64url>","tag":"<b64url 16B>"}
// device → phone
{"type":"pairing_encrypted","session_id":"<b64url>","counter":"0","ciphertext":"<b64url>","tag":"<b64url 16B>"}
```
* `counter` is a **decimal string**. It must match `^[0-9]+$`, be less than 2^64, and **equal**
  the expected receive counter. There is no window: a skipped or replayed counter is fatal.
* Each direction starts at 0 and increments by 1 per record.
* Base64url decoding must be strict (`b64url_decode`): unpadded, alphabet `[A-Za-z0-9_-]`,
  `len % 4 != 1`, length 1..4096 (ciphertext up to 16384). It rejects `=`, `+`, `/` and spaces.
  Encoding strips padding.
* Any failure clears the session, sends `error_pairing_decrypt` where possible, and disconnects
  after 0.3 s. Failures are: wrong `session_id`, bad counter, bad tag length, tag mismatch,
  expired, or IDLE.

#### Step B: `pairing_client_finished` (first record, counter 0)

Plaintext must be **exactly** `{"action":"pairing_client_finished"}`, with no extra keys. It must
be the first record (rx counter goes to 1 after decrypt), arrive in `WAIT_CLIENT_FINISHED`, and
not be expired. Then:
* The generation increments, state becomes `READY`, and the deadline is now + 120 s
  (`CONFIRMED_TIMEOUT_S`).
* Device sends the encrypted status: `{"type":"status","status":"pairing_confirmed","sdk_token":"mgst_…"}`.
  `sdk_token` is omitted when there is no token. "Apps read only type and status, so older ones
  ignore the token."

If the first decrypted record is anything else, the result depends on the action:
* A sensitive action gets `error_pairing_confirm_required`, and the session can never confirm.
* A later client_finished gets `error_pairing_decrypt` plus a disconnect.

#### Step C: `wifi_scan`

The phone sends encrypted `{"action":"wifi_scan"}`. The device replies with encrypted
`wifi_scan_result` (§2.5).

#### Step D: `provision_v2` (encrypted)

Fields read by the device:

| Field | Required | Notes |
|---|---|---|
| `ssid` | must be a string | ignored |
| `password` | must be a string (may be `""`) | ignored and never stored |
| `access_token` | non-empty string | device access token |
| `refresh_token` | non-empty string | may already carry the `hatch_refresh:` prefix |
| `token_type` | must equal `"device"` | |
| `username` | optional string | stored, unused |
| `api_url` | optional | legacy. Only older firmware reads it (and appends `/hatch/`). Ignored for API calls. |
| `api_url_v2` | optional | API root if it starts with `https://`, else the default `https://api.muse.ai` |
| `noise_host` | optional | WSS host. Default `hatch.metaaivm.com`. |

Test fixture (`tests/test_ble_setup.py`):
```python
PROVISION = {"action": "provision_v2", "ssid": "HomeNet", "password": "",
    "access_token": "device-access", "refresh_token": "device-refresh", "token_type": "device",
    "username": "someone", "api_url": "https://legacy-api.example", "api_url_v2": "https://api.example"}
```
The phone sends **no** `device_id` and no VM info. The phone (Muse app) mints the device token
server-side, presumably keyed on the `node_id`/`device_id` and `sdk_token` it learned during
pairing (that part happens outside this code).

Sequence:
1. Validate the fields. On failure, send `error_missing_credentials` and stay `READY`.
2. If another provision is running, send `error_operation_in_progress`.
3. `mark_provisioning()`: `READY` becomes `PROVISIONING`, the generation increments, and the
   deadline is now + 120 s. If this fails, send `error_pairing_confirm_required`.
4. On a worker thread:
   1. Send `wifi_connecting`.
   2. If offline: send `wifi_failed`, extend the deadline by 120 s, and stop (the app can send
      `provision_v2` again).
   3. Send `wifi_connected`.
   4. Verify: `GET {api_root}/fetch_vms` with the new `access_token`. Zero VMs or an error
      sends `auth_failed` and disconnects after 0.5 s.
   5. Commit `pairing.json` under the pairing lock, only if the generation is still current and
      the session is still `PROVISIONING`. Otherwise send `error_storage`.
   6. Send `auth_ok` and call `on_complete`. `pair.finish()` stops the BLE server after
      `BLE_SHUTDOWN_DELAY_S = 1.5` s.

Expected status sequence for a happy path (`test_full_setup_flow`):
`["pairing_confirmed", "wifi_connecting", "wifi_connected", "auth_ok"]`.

### 2.7 Pairing state machine

```
IDLE --hello ok--> WAIT_CLIENT_FINISHED (60 s) --client_finished--> READY (120 s)
READY --provision_v2--> PROVISIONING (120 s; extended by wifi_failed)
any --deadline passes--> keys dropped (generation kept) ; any --decrypt failure / disconnect / new hello--> IDLE (generation++)
```
*Generation* is a counter. Deferred work (provisioning) holds a generation, and every encrypt
or commit checks it. An abandoned or expired attempt can then never send or commit into a newer
one. `encrypt_json` returns `None` (nothing is sent) on a stale generation, an expired session,
or IDLE.

### 2.8 Disconnect

`on_disconnect` resets the BLE chunk assembler, clears `plaintext_blocked`, and resets the
pairing session (IDLE, generation++). A reconnecting phone starts again from `get_device_info`.

### 2.9 Pairing window and when to pair

```python
SETUP_WINDOW_S = 600        # pair.py: 10 minutes; CLI --timeout overrides
BLE_SHUTDOWN_DELAY_S = 1.5
```
* `pair()` refuses (`ALREADY_PAIRED`) if `pairing.json` exists, unless `force`.
* An invalid SDK token fails before BLE opens.
* Advertising runs only during the window. When the window ends, the GATT server and
  advertisement are unregistered and the adapter Alias/Pairable are restored.
* The user flow in the Muse app: Settings > Devices > **Developer mode** on > Add Device >
  pick `MuseGadgetXXXXXX` > accept the community-device warning > choose the offered network.

---

## 3. After pairing: cloud protocol

### 3.1 API base and VM lookup (`muse_api.py`)

```python
API_BASE = "https://api.muse.ai"
FETCH_PATH = "/fetch_vms"
REFRESH_PATH = "/device_token/refresh"
def api_root(api_url_v2=""): return (api_url_v2 or API_BASE).rstrip("/")
```
`User-Agent: musegadget/0.1.0 (<model>; <OS> <arch>) Python/<ver>`. It is informational; choose
your own, e.g. `musegadget/0.1.0 (TCL …; Android aarch64)`.

**GET `{root}/fetch_vms`**
```
Authorization: Bearer <access_token>
X-API-Version: 1.0.0
User-Agent: …
(timeout 15 s)
```
Response JSON (fields read):
```json
{"vm_list":[{"vm_ws_url":"…","vm_url":"…","vm_auth_token":"…","vm_name":"…","vm_id":"…","default":true}]}
```
* The call fails if the body is not an object, has `error_title` or `backend_error_code`, or has
  no `vm_list` list.
* An entry is kept only if `(vm_ws_url or vm_url)` and `vm_auth_token` are both truthy.
* The VM used is the first with `default == true`, else `vms[0]`.
* **Note:** `vm_url`/`vm_ws_url` is captured but **not used** for the connection. The WSS host
  is `pairing.noise_host` or `"hatch.metaaivm.com"`. The VM is picked by the `vm_id` query
  parameter (falling back to `vm_name` if `vm_id` is empty).
* HTTP 401 means the device token was rejected: force a refresh. No HTTP response (transport
  error) means retry with backoff.

### 3.2 Device token refresh

**POST `{root}/device_token/refresh`**
```
Authorization: Bearer hatch_refresh:<raw_refresh>
Content-Type: application/json
User-Agent: …
Body: {"device_id":"<node_id e.g. homelink-abcdef>","sdk_token":"mgst_…"}   # sdk_token omitted if none
(timeout 15 s)
```
* `raw_refresh = refresh_token.rsplit(":", 1)[-1]`. If the stored token already has the
  `hatch_refresh:` prefix, strip it so the prefix is never doubled.
* Never present the access token here. Upstream: "the server can accept it and answer 200 with
  replacement tokens that every endpoint then rejects".
* Response: `{"access_token":"…","refresh_token":"…"}`, or the same wrapped as
  `{"payload":{…}}`. Both tokens must be non-empty.
* HTTP 401 means the pairing is revoked: delete `pairing.json` and require re-pairing. Any other
  failure is transient: keep using the current token, and if the refresh was forced, sleep
  `TOKEN_RETRY_S`.

Timing (`service.py`):
```python
TOKEN_REFRESH_AGE_S = 3 * 3600   # "Device access tokens live about 4 hours; rotate at 3."
TOKEN_RETRY_S = 300              # min spacing between non-forced attempts; sleep after failed forced refresh
```
The test `test_muse_api.py` asserts the exact header and body:
`("Bearer hatch_refresh:r", {"device_id": "homelink-abcdef", "sdk_token": "mgst_token"})`.

### 3.3 Service loop (`service.py › Service.run`)

```python
DEFAULT_NOISE_HOST = "hatch.metaaivm.com"
BACKOFF_BASE_S = 2.0
BACKOFF_MAX_S = 60.0
AUTH_BACKOFF_MIN_S = 15.0
HEALTHY_SESSION_S = 30.0
UNPAIRED_POLL_S = 30.0
```
Each round:
1. If there is no `pairing.json`, sleep 30 s and loop.
2. If the token is ≥ 3 h old, refresh it. A 401 deletes the pairing.
3. `fetch_vms`. On 401, force a refresh and `continue`. With no VM, sleep for the backoff and
   loop.
4. Run one `LinkSession` (§3.4). Map the outcome:
   * `STOPPED`: return.
   * `UNPAIRED`: delete `pairing.json` ("pairing removed; run pair").
   * If the session was registered for ≥ 30 s, reset the backoff.
   * `AUTH_REJECTED` (upgrade 401) or `FORBIDDEN` (403, control-stream 403, or register
     rejected): backoff floor 15 s.
   * Sleep `backoff.next_delay()`: 2, 4, 8, 16, 32, 60, 60 … (`min(2·2^min(n,16), 60)`, then
     `max(…, floor)`).
5. Every new round re-fetches VMs. That gives a fresh `vm_auth_token`, which is the required
   reaction to an upgrade 401/403. Upstream: "A 401 or 403 on the upgrade means fetch fresh VM
   credentials, not retry the same bearer."

### 3.4 Link session (`link_client.py`)

```python
NOISE_PATH = "/v1/noise"
CONTROL_PATH = "/link-control"
CHAT_PATH = "/chat/stream"
APP_ID = "musegadget"
REQUEST_TIMEOUT_S = 60
MAX_RESPONSE_BYTES = 1024 * 1024
HANDSHAKE_TIMEOUT_S = 20
PING_INTERVAL_S = 20
MAX_CONCURRENT_INVOKES = 4
MAX_INBOUND_MESSAGE = 4 * 1024 * 1024
MAX_CHAT_EVENT_BYTES = 256 * 1024
MAX_STREAM_BUFFER_BYTES = 1024 * 1024
MAX_TTS_BUFFER_BYTES = 8 * 1024 * 1024
STREAM_CHUNK_BYTES = 16 * 1024
MAX_VOICE_BYTES = 2 * 1024 * 1024
REQUEST_CHUNK_BYTES = 16 * 1024
MAX_INLINE_REQUEST_BYTES = 64 * 1024
_URI_COMPONENT_SAFE = "-_.!~*'()"     # vm_id escaped like JS encodeURIComponent
```

#### 3.4.1 WebSocket

* URL: `wss://{noise_host}/v1/noise?vm_id={encodeURIComponent(vm_id)}`.
  Test: `noise_url("h", "a-b_c.d!~*'()?&=") == "wss://h/v1/noise?vm_id=a-b_c.d!~*'()%3F%26%3D"`;
  `vm_id="vm 1&x"` → `vm%201%26x`. Note that Java's `URLEncoder` uses `+` for spaces and escapes
  `!~*'()`, so write your own encoder.
* Upgrade request headers: `Authorization: Bearer <vm_auth_token>` and `User-Agent`. Open
  timeout 20 s.
* Upgrade response 401 → `AUTH_REJECTED`. 403 → `FORBIDDEN`. Other failures → exception →
  `CLOSED`.
* Keepalive: the `websockets` library sends a **WebSocket ping every 20 s** and closes if there
  is no pong within 20 s. The client must also answer server pings with pongs.
* There is no message size limit (`max_size=None`). All protocol messages are **binary** frames.
  A text frame is an error during the handshake and is ignored afterwards.
* There is **no application-level keepalive** from the device. The VM may send zero-length
  length-prefixed messages on the control stream as keepalives; ignore them (§3.4.5).
* TLS uses the system trust store. There is no certificate pinning.

#### 3.4.2 Noise XX handshake (`noise/noise_xx.py`)

* `PROTOCOL_NAME = b"Noise_XX_25519_AESGCM_SHA256"` (28 bytes). It is zero-padded to 32 to form
  the initial `h`, and `ck = h`. Then `MixHash(b"")`, i.e. an empty prologue. This is the
  standard Noise spec, rev 34.
* The device is the **initiator**. Its static key is **generated fresh for each handshake**
  (`write_message3` makes a new X25519 pair). The device has no long-term Noise identity;
  "The bearer already authenticated us at the upgrade; message 3 carries an empty payload."
* The responder's static key (`rs`) is **not verified or pinned**.
* Messages, each sent as **one binary WS message**:
  * msg1 (device → VM): `e` = 32 bytes. Payload is empty and there is no key yet, so msg1 is
    exactly 32 bytes.
  * msg2 (VM → device): `e(32) || enc(s)(32+16) || enc(payload)(≥16)`, minimum 96 bytes
    (`MIN_MSG2_LEN`). Tokens: `e, ee, s, es`. The payload is decrypted and discarded.
  * msg3 (device → VM): `enc(s)(48) || enc(empty payload)(16)` = 64 bytes. Tokens: `s, se`.
* `Split()`: `k1, k2 = HKDF(ck, b"", 2)`. The **initiator sends with k1 and receives with k2**.
* HKDF (Noise): `temp = HMAC-SHA256(ck, ikm)`, `out1 = HMAC(temp, 0x01)`,
  `out2 = HMAC(temp, out1 || 0x02)`.
* Cipher: AES-256-GCM, nonce = `00 00 00 00 || u64be(n)`, 16-byte tag. Handshake AD = `h`.
  Transport AD = **empty**.
* The nonce counter is separate per direction and starts at 0. The cipher is poisoned at
  `MAX_SAFE_NONCE = (1<<53)-1`, or after any decrypt failure. A dead transport ends the session.
* DH checks: reject a peer public key that equals one of the listed low-order points
  (`X25519_LOW_ORDER_POINTS`: all-zero, `01 00…`, `e0eb7a7c…b800`, `5f9c95bc…1157`,
  `ecff…7f`, `edff…7f`, `eeff…7f`). Reject an all-zero shared secret.
* Handshake timeout 20 s (`HANDSHAKE_TIMEOUT_S`).
* Full fixed-key vector: `muse_extra_vectors.json › noise_xx`.

#### 3.4.3 Transport framing, layer by layer (`noise/transport.py`, `framing.py`, `envelope.py`)

Outbound (device → VM), per logical frame:
```
ServiceFrame  (protobuf)            ← stream_id + one of request/response/body_chunk/reset
  wrapped in ServiceRequest {service=DAEMON(0, omitted), payload=ServiceFrame bytes}
  split into NoiseTransportFrame(s) {chunk_id=random int64, chunk_index, total_chunks, payload ≤ 65489 B}
  each NoiseTransportFrame bytes → AES-GCM encrypt (AD empty) → one WS binary message
```
Inbound (VM → device):
```
WS binary message → AES-GCM decrypt → NoiseTransportFrame → reassemble by chunk_id
  → ServiceResponse {payload} (payload must be non-empty) → ServiceFrame
  → kind response | body_chunk | reset   (a "request" from the server is a protocol error)
```

Hand-written protobuf (proto3 semantics: default/zero fields are omitted on encode, unknown
fields are skipped on decode, varints up to 10 bytes, field numbers 19000–19999 are invalid):

```
message NoiseTransportFrame { int64  chunk_id = 1; uint32 chunk_index = 2; uint32 total_chunks = 3; bytes payload = 4; }
   // decode default total_chunks = 1 when absent
message ServiceRequest      { ServiceType service = 1; bytes payload = 2; }
message ServiceResponse     { bytes payload = 1; }
message ServiceFrame        { int64 stream_id = 1;
                              oneof kind { ApplicationRequest request = 2; ApplicationResponse response = 3;
                                           BodyChunk body_chunk = 4; Reset reset = 5; } }
message ApplicationRequest  { string verb = 1; string path = 2; repeated Header headers = 3; bytes body = 4; bool end_body = 5; }
message ApplicationResponse { int32 status = 1; repeated Header headers = 2; bytes body = 3; bool end_body = 4; }
message BodyChunk           { bytes data = 1; bool end_body = 2; }
message Reset               { ResetCode code = 1; string reason = 2; }
message Header              { string key = 1; string value = 2; }
enum ServiceType { SERVICE_DAEMON = 0; SERVICE_SENTINEL = 1; SERVICE_VAULT = 2; SERVICE_AUTHD = 3; }
enum ResetCode   { CODE_UNSPECIFIED = 0; CANCELLED = 1; TIMEOUT = 2; PROTOCOL_ERROR = 3;
                   REFUSED_STREAM = 4; INTERNAL_ERROR = 5; SERVICE_UNAVAILABLE = 6; }
```
* int64 values are encoded as 10-byte two's-complement varints when negative. `chunk_id` is
  random over the full int64 range, so about half are negative. int32 negatives are also
  sign-extended to 64 bits.
* Example: `chunk_id=-2` → `08 fe ff ff ff ff ff ff ff ff 01`.
* The encoder writes `chunk_index` only when nonzero, `total_chunks` always (it is ≥1), and
  `payload` only when non-empty.
* Constants: `MAX_CHUNK_PAYLOAD = 65489`, `MAX_TOTAL_CHUNKS = 256`,
  `MAX_PENDING_ASSEMBLIES = 16`, `MAX_ASSEMBLY_BYTES = 16 MiB`, `ASSEMBLY_TTL_SECONDS = 60`.
* Reassembly accepts chunks out of order (keyed by index). A duplicate index or inconsistent
  total is an error that poisons the decoder.

**Streams:**
* `stream_id` starts at **1** and increments per request. Requests use only the `daemon`
  service.
* A one-shot request (`encrypt_http_request`) is one `ApplicationRequest` with the body inline
  and `end_body=true`.
* A streaming request (`start_stream_request`) is an `ApplicationRequest` with an empty body and
  `end_body=false`, followed by `BodyChunk` frames on the same stream id. The last one has
  `end_body=true` (if it ever ends).
* To cancel a stream, send `Reset{code=CANCELLED(1), reason="consumer closed"}`.
* Responses come back as an `ApplicationResponse` (status, headers, optional body, `end_body`),
  then zero or more `BodyChunk`s, until `end_body=true`, or a `Reset`.
* Frames for unknown stream ids are dropped.

**Ordering and concurrency:** encryption advances the send nonce, so **encrypt and write must
be done in the same order under one lock**. Upstream also makes sure a cancelled multi-frame
send is completed, so no nonce gap is left (`_send_frames`). In Kotlin, put "encrypt all frames
of a message + write them to the socket" inside a single mutex.

**Headers on device-originated requests** (`_http_headers`): `x-request-id: <uuid4>` and
`x-app-id: musegadget`, plus per-call headers. The `/link-control` request carries **no
headers**.

#### 3.4.4 Control stream `/link-control`

1. Right after the handshake, open stream 1: `ApplicationRequest{verb="POST", path="/link-control", end_body=false}`.
2. Send `link.register` as a `BodyChunk` on stream 1. The data is a **u32 little-endian length +
   compact JSON**:
```json
{"type":"req","id":"<uuid4>","method":"link.register","params":{
  "node_id":"homelink-abcdef",
  "display_name":"<hostname>",
  "platform":"linux",
  "version":"0.1.0",
  "device_family":"homehub",
  "model_id":"linux",
  "is_wakeup_supported":false,
  "commands_v2":{ "<command>": {"description":"…","required":{…},"optional":{…},"timeout_ms":…}, … }}}
```
   Upstream rule: "Register as `platform: "linux"`, `device_family: "homehub"`. Never use family
   `link` or advertise `device.ota`: the server pushes ESP32 firmware updates to every `link`
   device."
3. The VM first sends `ApplicationResponse` on stream 1. If `status >= 400`, stop: 403 →
   `FORBIDDEN`, others → `CLOSED`. The response body and later BodyChunks carry length-prefixed
   JSON messages.
4. Register reply: `{"type":"res","id":"<register id>","ok":true}`. It is matched by
   `id == register_id` and **no `method` key**. `error` present or `ok == false` → `FORBIDDEN`.
   Otherwise the device is registered: `registered_at` is set, and the log says "registered with
   the Muse".
5. Invoke: `{"method":"link.invoke","id":"inv-1","command":"system.run","params":{…},"timeout_ms":5000}`
   (a `type` field may also be present; it is not checked). Ignore it if `id` is missing.
   `params` falls back to `{}` if it is not an object. At most 4 invokes run concurrently.
6. Result: `{"method":"link.result","id":"<invoke id>","ok":true,"payload":{…}}` or
   `{"method":"link.result","id":"…","ok":false,"error":"<message>"}`. It is sent as a BodyChunk
   on stream 1.
7. Unpair: any message with `event` in (`"link.unpaired"`, `"node.unpaired"`) → `UNPAIRED`.
   Test form: `{"type":"evt","event":"link.unpaired"}`.
8. A `Reset` on stream 1 or `end_body=true` on stream 1 → `CLOSED`. So does a WS close or recv
   error.
9. Size limit: "The VM accepts at most 256 KB per message from the device".
10. Heartbeat (ESP32 firmware, not the Linux SDK): when the register reply is
    `{"type":"res","id":…,"result":{"node_id":…,"status":"registered"}}`, send
    `{"method":"link.heartbeat"}` once, then once a day. The TV does this; it is harmless and
    had no visible effect on command routing.

**Observed on the live backend (2026-10-20, verified on the TV): commands arrive as
`client.invoke` chat events, not `link.invoke`.** With the TV registered and connected, Muse's
device tools ran into 30 s timeouts, and no `link.invoke` ever reached `/link-control` (the
symptom in muse-gadget-sdk issue #154). Instead the backend sends this on the **newest**
`/chat/subscribe` stream of the device's Noise session:
```json
{"type":"event","event":"client.invoke","ts_ms":…,
 "payload":{"command_id":"tv.show_message","invoke_id":"<uuid>","params_json":"{\"text\":\"Hello\"}","timeout_ms":30000}}
```
* It carries no chat or device id. It goes to one subscription only: with two open, only the
  most recently opened one got it, and closing that one lost the invoke (no fallback).
* Answering it with a normal `{"method":"link.result","id":"<invoke_id>","ok":…,…}` on the
  `/link-control` stream completes the call: Muse reported the result.
* So the TV keeps exactly one chat subscription per session, opened right after registration
  (scoped to its side chat; account-wide with `{}` until the side chat exists), and answers
  `client.invoke` for its own `commands_v2` names, deduplicated by `invoke_id`.
* `approvals.snapshot` events (`{pending_approvals:[], recent_approvals:[…]}`) also arrive;
  device commands needed no approval.

#### 3.4.5 Length-prefixed message codec (`encode_message` / `MessageDecoder`)

```python
def encode_message(obj): data = json.dumps(obj, separators=(",", ":")).encode(); return struct.pack("<I", len(data)) + data
```
Decoder rules:
* Buffer across chunks.
* A length > 4 MiB is fatal.
* A **zero length is a keepalive** and is skipped.
* Malformed JSON or non-UTF-8 is dropped.
* Messages that are not objects are dropped.

#### 3.4.6 Sending a user message (`send_chat`) → `POST /chat/stream`

The request goes on its own stream in the same Noise session. That is how the VM attributes it
to this registered device.

```
ApplicationRequest{verb="POST", path="/chat/stream", end_body=true,
  headers=[Content-Type: application/json, x-request-id: <uuid4>, x-app-id: musegadget],
  body=JSON}
```
Body:
```json
{"message":"porch light on","output_modality":"text","device_id":"homelink-abcdef","session_id":"side-1"}
```
* `device_id` = node_id.
* `session_id` is optional and picks a **side chat**: "a new id starts one, and reusing it
  keeps later messages there". Without it, the message goes to the main chat. The local socket
  validates it as `[A-Za-z0-9-]{1,64}` (UUIDs in examples).
* `"chat_id"` is not an API field.
* `output_modality`: `"text"` (default) or `"voice"`. Voice is needed for TTS.
* If the body is larger than 64 KiB, it is sent as `start_stream_request` (same headers),
  followed by BodyChunks of ≤16 KiB, the last with `end_body=true`.
* The response is collected until `end_body`. Limits: 1 MiB, 60 s timeout. The result is
  `{"ok": 200<=status<300, "status": status, "response": <parsed JSON or text≤2000>}`. On
  timeout or cancel, the stream is reset.
* The ack holds the user message id, e.g. `{"result":{"message_id":"user-1"}}`. It can also be
  flat. Fields used by `MuseTurn`: `result.message_id`, `result.reply_to_message_id`,
  `result.session_id`, and `result.is_thread` (true for a side chat).
* The reply is **not** in this response. It shows up in the Muse chat and on `/chat/subscribe`.

Voice note (`send_voice`): the body is
`{"message":"<text or empty>","device_id":…,"session_id"?:…,"output_modality"?:…,"items":[{"type":"file","mime_type":"audio/wav","filename":"voice_note.wav","data_base64":"<std base64 WAV ≤ 2 MiB>"}]}`.

#### 3.4.7 Subscribing to chat events → `POST /chat/subscribe` (NDJSON)

```
ApplicationRequest{verb="POST", path="/chat/subscribe", end_body=true,
  headers=[Content-Type: application/json, Accept: application/x-ndjson, x-request-id, x-app-id],
  body = {"session_id":"<id>"}  or  {}}
```
* The stream is ready when `ApplicationResponse` arrives. A non-2xx status is an error.
* The body is **newline-delimited JSON**. Lines can be split across chunks, even mid-UTF-8.
* A single event over 256 KiB is an error (the subscription is cancelled with a Reset).
* Blank lines are skipped, and malformed lines are dropped.
* **Only objects with `"type":"event"` are delivered.** Others, such as `{"type":"ack"}`, are
  ignored.
* A final unterminated line at end-of-stream is still parsed.
* A slow consumer that falls more than 1 MiB behind gets the stream failed and reset; the
  control stream is unaffected.

Event envelope (from tests):
```json
{"type":"event","seq":1,"event":"delta.text_append","session_id"?:"…","message_id"?:"…",
 "payload":{"session_id":"chat","message_id":"r1","reply_to_message_id":"u1","text":"Hel"}}
```

#### 3.4.8 Following a turn (`muse_turn.py`)

`MuseTurn` is a pure library helper; the CLI doesn't use it. It is the reference for turning raw
events into a reply UI. The recommended order is:
1. Open `subscribe_chat(session_id)` and **create the turn before sending**.
2. `send_chat`.
3. `turn.acknowledge(ack_json)`.
4. Feed every event.
5. Stop when `turn.complete(now)`.

Events that arrive before the ack are held (max 64 events and 64 KiB of text) and released on
acknowledge.

Event names handled:
```python
_STATUS_EVENTS  = ("agent.status", "task.status")
_MESSAGE_EVENTS = ("delta.message_start", "delta.text_append", "delta.message_done", "message.assistant")
_ACTIVITY_CODES = {"working","responding","idle","online","thinking","researching","planning",
                   "listening","speaking","processing","generating","executing"}
_TERMINAL_TASK_STATUSES = {"completed","failed","errored","cancelled","canceled"}
```

Payload fields read:

| Field | Used for |
|---|---|
| `payload.session_id` (or envelope `session_id`) | chat filter; must equal the turn's session |
| `payload.message_id` / envelope `message_id` / `payload.id` | assistant message id |
| `payload.reply_to_message_id` / `payload.parent_message_id` | binds a reply to the user message (or to an earlier bound reply) |
| `payload.role` | `"user"` messages are ignored |
| `payload.text` | `delta.text_append`: text to append |
| `payload.display_text` | final text (preferred) on `delta.message_done` / `message.assistant` |
| `payload.display_text_ready` | `message.assistant` with `false` is not done yet |
| `payload.transcript` | final text fallback: a string, or `{"messages":[{"id","role":"assistant","content":[{"type":"text","text":…}]}]}` (assistant text items joined with `\n`, last assistant message or the one matching message_id) |
| `payload.content` | final text fallback when transcript is absent or empty |
| `payload.activity_code`, `payload.activity_text` | `agent.status` → `AgentStatus(code, text)` |
| `payload.status`, `payload.task_id` | `task.status` → `TaskFinished` when the status is terminal |

Turn output types:
* `AgentStatus(activity_code, activity_text)`: emitted on `agent.status` if the event is linked
  to the request or arrives on an owned side chat. Unknown codes count as `"other"` for
  `activity_code`. An unlinked status only updates `busy`.
* `Reply(message_id, text, streamed, done, bound)`: emitted on every message event for a bound
  or allowed assistant message.
  * `text` = the deltas so far, replaced by the final text.
  * `streamed` = the deltas only.
  * `done` = true on `delta.message_done`, or on `message.assistant` unless
    `display_text_ready == false`. Deltas after done are ignored.
  * `bound` = proven to answer this request.
  * On a shared chat, a reply with no parent is accepted but `bound=false`.
* `TaskFinished()`: emitted once, when a `task.status` arrives with a terminal status. In an
  owned side chat, it only fires once no tracked task is still running.
* Control characters (C0/C1 except `\t` and `\n`) are stripped from all text.
  Limits: ≤ 32 messages per turn, ≤ 64 KiB text per message, IDs ≤ 512 bytes.

Completion:
```python
REPLY_QUIET_S = 3.0          # silence after which a turn without terminal task status counts as answered
DETAIL_REPLY_QUIET_S = 30.0  # unanswered follow-up timeout
EMPTY_REPLY_GRACE_S = 1.0
complete(now) = not busy and all replies done and (task_finished or now-last_activity >= 3 s)
                and (no unanswered follow-ups or now-last_activity >= 30 s)
```
`busy` is true while `activity_code` is not in {"", "online", "idle"} and not terminal (or the
status is non-idle and non-terminal).

For a gadget that owns a side chat (`owns_chat=True`):
* The ack must have `result.session_id == session_id` and `result.is_thread == true`.
* Use a shared `ReplayScope` (up to 2048 IDs) to drop replays of earlier turns.

Simplest TV usage:
* Show `Reply.text` for bound replies. Show `AgentStatus.activity_text` as a "thinking…" line.
* Stop on `complete()`.

#### 3.4.9 TTS (optional)

* `GET /api/voice/tts-stream?message_id=<encodeURIComponent(id)>` with `Accept: audio/mpeg`.
* On **404**, reset the stream and retry `GET /voice/tts-stream?message_id=…`.
* The response body is a stream of MP3 bytes, buffered up to 8 MiB.
* It only works for replies to a request sent with `output_modality: "voice"`.

#### 3.4.10 Outcomes and errors summary

| Condition | Outcome | Service reaction |
|---|---|---|
| WS upgrade 401 | `AUTH_REJECTED` | backoff ≥ 15 s, re-fetch VMs (new bearer) |
| WS upgrade 403 | `FORBIDDEN` | same |
| `/link-control` response 403 | `FORBIDDEN` | same |
| `/link-control` response other ≥ 400 | `CLOSED` | normal backoff |
| register reply with `error` / `ok:false` | `FORBIDDEN` | backoff ≥ 15 s |
| `link.unpaired` / `node.unpaired` | `UNPAIRED` | delete pairing → must re-pair over BLE |
| reset / end_body on stream 1, WS closed, decrypt or proto error | `CLOSED` | backoff 2…60 s |
| `fetch_vms` 401 | — | forced token refresh |
| refresh 401 | — | delete pairing → re-pair |
| local stop | `STOPPED` | exit |

---

## 4. Commands Muse can invoke (`executor.py`, `fileops.py`)

### 4.1 Declaring commands: `commands_v2`

`commands_v2` is a dict of `name → spec`. A spec is:
* `description` (string, shown to the model).
* `required` and `optional`: dicts of param → `{"type": "string|integer|boolean", "description": …}`.
* An optional `timeout_ms` (the server-side invoke timeout; default 30 s per AGENTS.md: "and
  `timeout_ms` if the default 30 seconds is too short").

Upstream adds a command by putting it in `COMMAND_SPECS` and handling it in `Executor.run`.
"Muse sees the new command after the service restarts and re-registers."

**Capability control: you decide what to offer.**
* The device advertises exactly the keys of `commands_v2`. To not offer a capability, leave it
  out. Leaving out `system.run` is the supported way to refuse shell access.
* Never include `device.ota`.
* If the VM invokes a command anyway, answer `{"ok": false, "error": "unsupported command: <name>"}`.
  This is the exact text `Executor.run` returns.
* Any failure is `{"ok": false, "error": "<message>"}`. Upstream never logs params or output.

**Mandatory vs optional:** nothing in the SDK makes any command mandatory. "Online/healthy" here
means only: Noise session up, `/link-control` accepted, and the `link.register` reply `ok`. The
SDK never sends a health heartbeat; liveness is the WS ping. `device.health` is the obvious
cheap command to keep, and upstream tests use it as a liveness probe. Whether the server accepts
an empty `commands_v2` is **unknown** (open question §8). Recommendation: register at least
`device.health` plus your TV-specific commands.

### 4.2 Upstream command specs (verbatim)

```python
DEFAULT_TIMEOUT_S = 120
MAX_TIMEOUT_S = 600
MAX_OUTPUT_BYTES = 96 * 1024

"system.run": {
    "description": ("Run a shell command on this Linux device with bash and return its "
                    "stdout, stderr and exit code. Output over 96 KiB per stream is truncated."),
    "required": {"command": {"type": "string", "description": "Shell command line to run."}},
    "optional": {
        "cwd": {"type": "string", "description": "Working directory. Default: the account's home."},
        "timeout_ms": {"type": "integer", "description": "Kill the command after this long. Default 120000, max 600000."},
    },
    "timeout_ms": MAX_TIMEOUT_S * 1000 + 5000,      # 605000
},
"file.read": {
    "description": "Read up to 64 KiB of a file, base64-encoded. Call again with next_offset until eof.",
    "required": {"path": {"type": "string", "description": "Absolute path."}},
    "optional": {
        "offset": {"type": "integer", "description": "Byte offset. Default 0."},
        "limit": {"type": "integer", "description": "Max bytes, up to 65536."},
    },
},
"file.write": {
    "description": ("Write a file in chunks of up to 64 KiB (base64). Start at offset 0, "
                    "send chunks in order, and set final=true on the last one; the file "
                    "is replaced atomically, and only if sha256 (when given) matches."),
    "required": {
        "path": {"type": "string", "description": "Absolute destination path."},
        "data_b64": {"type": "string", "description": "This chunk's bytes, base64-encoded."},
    },
    "optional": {
        "offset": {"type": "integer", "description": "Byte offset of this chunk. Default 0."},
        "final": {"type": "boolean", "description": "True on the last chunk. Default false."},
        "sha256": {"type": "string", "description": "Hex SHA-256 of the whole file, checked on the final chunk."},
        "create_parents": {"type": "boolean", "description": "Create missing parent directories. Default false."},
        "overwrite": {"type": "boolean", "description": "Replace an existing file. Default true."},
    },
},
"device.health": {
    "description": "Device status: uptime, load, memory, disk, temperature, software version.",
    "required": {},
    "optional": {},
},
```

### 4.3 Request and response shapes

Invoke → `run_command(command, params, timeout_ms)` → result dict merged into `link.result`.

**system.run** (do NOT implement on the TV; listed for completeness)
* params: `command` (str, required), `cwd`, `timeout_ms`. The effective timeout is
  `params.timeout_ms or invoke.timeout_ms`, default 120 s, capped at 600 s.
* ok payload: `{"stdout": str, "stderr": str, "exit_code": int, "timed_out": bool, "truncated": bool, "duration_ms": int}`.
  Each stream is clipped to 96 KiB, measured as JSON-escaped size.
* Errors: `"command is required"`, `"could not start command: …"`.

**file.read**
* params: `path` (absolute), `offset` (default 0), `limit` (default and max 65536).
* ok payload: `{"path", "size" (stat size), "offset", "next_offset", "eof": bool, "data_b64": std base64}`.
  `eof` is found by reading `limit+1` bytes, not from the stat size.
* Errors: `"path must be an absolute path"`, `"offset must be >= 0 and limit > 0"`, or OS error
  text.

**file.write**
* params: `path`, `data_b64` (std base64, strictly validated, ≤ 64 KiB decoded), `offset`,
  `final`, `sha256`, `create_parents`, `overwrite` (default true).
* Writes go to `.<name>.musegadget-partial` next to the target (mode 0600). Chunks must arrive
  in order; `offset` must equal the current partial size.
* Non-final ok: `{"path", "received": <bytes so far>, "complete": false}`.
* Final ok: `{"path", "size", "sha256": hex, "complete": true}`, after the SHA check and atomic
  rename. The file keeps the old file's mode without suid/sgid/sticky bits; a new file gets
  0644 minus umask.
* Errors: `"no write in progress; start at offset 0"`, `"expected offset N, got M"`,
  `"sha256 mismatch; write discarded"`, `"file exists and overwrite is false"`,
  `"chunk larger than 65536 bytes"`.

**device.health**
* ok payload (each field best-effort): `{"version": "0.1.0", "hostname": str, "uptime_s": int, "load": [f,f,f], "memory_mb": {"total", "available"}, "disk_gb": {"total", "free"}, "temperature_c": float, "model": str}`.
* Tests require only `version`, `hostname`, and `disk_gb`.
* Android mapping: `SystemClock.elapsedRealtime()`, `ActivityManager.MemoryInfo`, `StatFs`,
  `Build.MODEL`, and `Settings.Global.DEVICE_NAME` for the hostname.

For a TV, consider defining your own commands such as `tv.launch_app`, `tv.media_key`, or
`tv.now_playing`. Use the same spec shape and the `ok`/`error` result shape.

---

## 5. `data/` folder

| File | What it is | Relevance |
|---|---|---|
| `data/musegadget.service` | systemd unit: `ExecStart=/opt/musegadget/venv/bin/musegadget run`, `Restart=always`, `RestartSec=5`, `StateDirectory=musegadget` (0700), `RuntimeDirectory=musegadget`, `Environment=MUSEGADGET_RUN_AS=@RUN_AS@` | none (Android uses a foreground service) |
| `data/requirements.lock` | hash-pinned `websockets` (15.0.1 for py<3.10, 16.1.1 for 3.10, 17.1 for ≥3.11) | tells you that the only third-party runtime deps are `websockets` and `cryptography`, plus `dbus`/`gi` for BLE |

There are **no certificates, public keys, or server pins** in `data/`, and none anywhere in the
Linux SDK. Server authenticity relies on TLS (WSS/HTTPS) with the system CA store. The ESP32
tree has a `dev_signing_key.pem`, which is for firmware signing and not relevant here.

---

## 6. Test vectors and fixtures

| File | Content | Use in Kotlin |
|---|---|---|
| `tests/vectors/link_pairing_v5.json` | 3 vectors (`community_v5`, `official_v5`, **`community_app_v5`** = ours). Fixed P-256 scalars (mobile=1, device=2), nonces `00..0f` / `10..1f`, plus `mobile_pub`, `device_pub`, `ecdh_secret_hex`, full `transcript`, `transcript_hash`, `session_secret_hex`, `mobile_tx_key_hex`, `mobile_rx_key_hex`, `session_id`, and the client_finished ciphertext/tag/aad | Transcript builder, SHA-256, P-256 point encoding, ECDH, HKDF schedule, session id, AES-GCM record decrypt. Copy it into `src/test/resources`. |
| `tests/test_pairing.py` | the `Mobile` class (a phone-side record layer "written from the contract"); hello validation table; counter, replay and tamper cases; timeouts (60 s / 120 s); generation checks; `b64url_decode` rejections (`""`, `"a"`, `"ab=c"`, `"ab+c"`, `"ab/c"`, `"a b"`); `b64url_encode(b"\xfb\xff") == "-_8"` | Port the cases one-for-one. `test_hello_produces_the_vector_pairing_ready` gives the exact `pairing_ready`. |
| `tests/test_ble_framing.py` | chunk sizes per MTU `(23→17, 185→157, 256→157, 3→17)`, headers `fe 00 03`…, empty message → `fe 00 01`, 255-chunk limit, reassembly edge cases | Port directly. |
| `tests/test_ble_setup.py` | `PROVISION` fixture, full flow status order, plaintext rules, disconnect delays (0.3 / 0.5), offline retry | Port as GATT-independent controller tests. |
| `tests/test_identity.py` | name derivation, locally-administered bit | Port directly. |
| `tests/test_config.py` | SDK token regex accept/reject: `"mgst_"+"A"*42+"w"` ok; `"mgst_short"`, `"mgst_"+"A"*43+"x"`, `"mgst_"+"A"*42+"B"` rejected | Port directly. |
| `tests/test_muse_api.py` | refresh header `Bearer hatch_refresh:r`, body with/without `sdk_token`, prefix de-duplication | Port directly. |
| `tests/test_link_client.py` | in-process **fake VM** (Noise responder + control stream). Covers register/invoke/result/unpair, URL escaping, `/chat/stream` body, NDJSON parsing with split UTF-8, TTS fallback, buffer limits, nonce preservation on cancel | Use as a script for a Kotlin fake-VM integration test. Port `NoiseXXResponder` (test-only, in `noise_xx.py`). |
| `tests/test_muse_turn.py` | about 20 event-sequence → expected `Reply`/`AgentStatus`/`TaskFinished` cases | Port directly if you port `MuseTurn`. |
| `tests/test_service.py` | backoff sequence `[2,4,8,16,32,60,60]`, floor 15, local socket rules | Port backoff. |
| `tests/test_executor.py` | file.write/read round trip, sha mismatch, offsets, modes, `unsupported command: nope` | Use only for `device.health` and whatever file ops you keep. |
| **`scratchpad/muse_extra_vectors.json`** (generated here from SDK code) | (a) BLE framing hex. (b) A device→phone `pairing_confirmed` record encrypted with `community_app_v5` keys: nonce `010000000000000000000000`, AAD, exact ciphertext and tag. (c) Protobuf hex for the `/link-control` request, the `link.register` BodyChunk, the `/chat/stream` request, a reset, and 200/403 responses. (d) `NoiseTransportFrame` with a fixed `chunk_id` (also a negative one). (e) **Noise XX with fixed X25519 keys** (`11…`, `22…`, `33…`, `44…`): msg1/msg2/msg3 hex, handshake hash, final ck, split k1/k2, and the first transport ciphertext | Byte-exact tests for the vendored protobuf, X25519, Noise, and GCM code. Regenerate with `PYTHONPATH=muse-gadget-sdk/linux/src python3 gen_vectors.py`. |

Key values (from `community_app_v5`, copied here for convenience):
```
mobile_pub     = BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU
device_pub     = BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E
mobile_nonce   = AAECAwQFBgcICQoLDA0ODw      device_nonce = EBESExQVFhcYGRobHB0eHw
ecdh_secret    = 7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978
transcript_hash= 18Rs196tzddPGuj18pZ8rWObWYpwoDUIwocjnUASj_0
session_secret = 560fa31813e6869852d75de6797b5a3221c2cfb5fddfefb87ceb7842d553e987
mobile_tx_key  = ee25e7f1eb3c05cc8465634e5f634deed858862b1c4c35fa434dc926f7facaeb   (device decrypts)
mobile_rx_key  = 9abb2954b05e2f5f7112e9bfd41fd970602e27ed07453c1cff51f575de5d0b4e   (device encrypts)
session_id     = z0eNLGw5mczvD4a1F2ubSQ
client_finished (counter 0): ciphertext uwNX3KEkix5T6nWce3s2SKPPh-5E31x-UIHFSK6MpMUg562B, tag zT4kU6XK-rM7rvt3VEITLg
  aad "hatch-link ble setup v1|z0eNLGw5mczvD4a1F2ubSQ|m2d|0"
identity: device_id hatch-link:02:00:00:00:00:01, node_id homelink-000001, mac 02:00:00:00:00:01, firmware 1.0.0
```
Noise XX fixed-key vector (excerpt):
```
init e priv 11×32 → pub 7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13 (= msg1)
resp e priv 33×32, resp s priv 44×32, init s priv 22×32
handshake_hash 26e1b63e47505accfcdf1e39f9392b527c9bf4130146a1fc6c21233eb7c2daa9
k1 (initiator send) eb2fe0bed643c43335b1354ec654dc80e37916a5b95f315e54435ddd476ef060
k2 (initiator recv) f6363bb26e6e9cb20ac77b14b6ec4ee9c92f3f1d9d1397468bb4ea23bed9d1d8
```

---

## 7. Primitives needed and Android 11 availability (minSdk 28, target device API 30)

| Primitive | Where | Android 11 platform | Notes |
|---|---|---|---|
| **P-256 key generation + ECDH** | BLE pairing | ✅ `KeyPairGenerator.getInstance("EC")` with `ECGenParameterSpec("secp256r1")`, plus `KeyAgreement.getInstance("ECDH")` (Conscrypt/BC) | The secret is the 32-byte X coordinate, same as Python. |
| P-256 point encode/decode (X9.62 uncompressed) | pairing | ⚠️ manual | Encode as `0x04 ‖ X(32) ‖ Y(32)` from `ECPublicKey.w`; left-pad `BigInteger.toByteArray()` to 32 bytes and strip the sign byte. Decode with `KeyFactory("EC").generatePublic(ECPublicKeySpec(ECPoint(x,y), params))`. **Validate the point is on the curve yourself** (`y² ≡ x³ − 3x + b mod p`) before ECDH. |
| **AES-256-GCM** | pairing records and Noise | ✅ `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, iv12)`, `updateAAD` | Output is `ciphertext‖tag`. Call `init` again for every operation; Conscrypt refuses IV reuse on an encrypt-mode instance. Bad tags throw `AEADBadTagException`. |
| **SHA-256** | transcript, salt, session id, Noise | ✅ `MessageDigest.getInstance("SHA-256")` | |
| **HMAC-SHA256** | HKDF, Noise HKDF | ✅ `Mac.getInstance("HmacSHA256")` | Noise HKDF can have an all-zero 32-byte key; that is fine for `SecretKeySpec`. Note: an **empty** key throws `IllegalArgumentException`, but no empty keys occur here. |
| **HKDF-SHA256** (RFC 5869) | pairing | ❌ not in javax.crypto on API 30 | Vendor about 20 lines on top of HMAC. Pairing uses extract+expand once and expand-only twice. |
| **X25519** | Noise | ❌ **not available on API 28–30**. `KeyAgreement "XDH"` and `KeyPairGenerator "XDH"` arrived in API 33 (Conscrypt). | **Vendor it**, e.g. a Kotlin port of TweetNaCl `crypto_scalarmult` or curve25519-donna (~150–250 lines). Clamp the scalar per RFC 7748. Test it against the RFC 7748 vectors and `muse_extra_vectors.json`. Do the low-order point and all-zero checks yourself. |
| Noise XX state machine | link | ❌ | Vendor (~200 lines). Port `noise_xx.py` 1:1, including the test `NoiseXXResponder`. |
| **Protobuf** (proto3 wire subset: varint, length-delimited, int64/int32/uint32/bool/bytes/string) | link | ❌ (no runtime lib allowed) | Vendor: port `_proto.py` + `envelope.py` + `framing.py` (~400 lines). Watch the int64 sign extension (`Long` in Kotlin is fine; emit 10-byte varints for negatives). |
| **WebSocket client (RFC 6455, wss)** | link | ❌ No public platform API. Android's internal `com.android.okhttp` is hidden, and `java.net.http` is not on Android. | **Vendor a minimal client** over `SSLSocketFactory.getDefault()` (SNI and hostname verification come by default via `HttpsURLConnection`'s `HostnameVerifier`; check this explicitly for raw `SSLSocket`). It must cover: the HTTP/1.1 upgrade with `Authorization`, `User-Agent` and a `Sec-WebSocket-Key`/`Accept` check; reading the 401/403 status line; masked client frames; binary frames and continuation; ping→pong; sending a ping every 20 s; a pong timeout; close handshake; and large payloads (64-bit length). About 300–400 lines. |
| HTTPS REST | fetch_vms, refresh | ✅ `HttpsURLConnection` | 15 s timeouts. Read the error code with `responseCode` (401 handling). |
| Base64url (unpadded) | pairing | ✅ `java.util.Base64.getUrlEncoder().withoutPadding()` (API 26+) or `android.util.Base64` (`URL_SAFE or NO_PADDING or NO_WRAP`) | **Decoding must be strict** (§2.6). Pre-validate with regex `^[A-Za-z0-9_-]+$`, `len%4 != 1`, and length bounds. `java.util.Base64.getUrlDecoder()` accepts missing padding but also accepts `=`, so validate first. |
| Base64 standard | file ops, voice | ✅ `java.util.Base64` | |
| JSON | everywhere | ✅ `org.json` | Check types strictly. `version` must be a numeric 5 and not Boolean/String; `optInt` coerces strings, so check `opt("version") is Number`. client_finished must be **exactly** one key. `JSONObject.toString()` escapes `/` as `\/`. That is valid JSON, and none of the formats here is a signed byte-exact JSON, so it is acceptable. Key order doesn't matter to parsers. |
| u32 little-endian length prefix | link-control | ✅ `ByteBuffer.order(LITTLE_ENDIAN)` | |
| SecureRandom | keys, nonces, chunk_id, UUIDs | ✅ `SecureRandom`, `UUID.randomUUID()` | |
| Ed25519 / ECDSA / CBOR / msgpack / ChaCha20-Poly1305 / BLAKE2 | — | not needed | ECDSA only appears in the unimplemented official pairing mode. |
| BLE peripheral | pairing | ✅ `BluetoothLeAdvertiser` + `BluetoothGattServer` (confirmed on the TV) | Needs the `BLUETOOTH`, `BLUETOOTH_ADMIN` and location permissions on API 30. |

---

## 8. Open questions, ambiguities, fragile spots

1. **BLE name format.** Linux uses `MuseGadgetABCDEF` (no hyphen); ESP32 uses `MuseGadget-ABCDEF`.
   The app evidently accepts the Linux form, so use it. The app's scan filter is unknown: name
   prefix, service UUID, or both. Advertise the UUID in the adv packet and the name in the scan
   response, as ESP32 does.
2. **Android cannot set the advertised local name on its own.** You must rename the TV's
   Bluetooth adapter (`BluetoothAdapter.setName`) during pairing and restore it after. This also
   sets the GAP Device Name, which upstream says "the phone reads". If the app or process dies
   mid-window, the TV keeps the `MuseGadgetXXXXXX` name. Persist the original name and restore
   it at the next start.
3. **GATT server details.** The default MTU on an Android server is 23 until `onMtuChanged`.
   The Python code *assumes 163* until a write reports the MTU. On Android, use the value from
   `onMtuChanged`, else 23. If the phone never negotiates, `pairing_ready` takes about 36
   notifications at 17 bytes each. That is fine; the 255-chunk cap allows 4335 bytes. Notifying
   faster than `onNotificationSent` drops packets, and any lost chunk kills the message.
4. **Notifications must go to the right device.** Python tracks one device path. Serve one
   client at a time and ignore writes from others.
5. **Server identity is not authenticated in Noise.** The responder's static key is never
   checked, and nothing is pinned. Security rests on TLS to `hatch.metaaivm.com` and
   `api.muse.ai`. BLE pairing is unauthenticated ECDH (community mode): it "can't prevent an
   active man-in-the-middle attack". The device and access tokens cross BLE protected only by
   that.
6. **`platform`/`model_id`/`device_family` values.** Upstream hard-codes `"linux"`, `"linux"`,
   `"homehub"`. It is unknown whether the server accepts `"android"`. Keep `"linux"`/`"homehub"`
   to be safe, and never use `device_family: "link"` (OTA pushes).
7. **Empty or minimal `commands_v2`.** Unknown whether the server accepts `{}`. No command is
   proven mandatory. Register `device.health` at least.
8. **`vm_url`/`vm_ws_url` is ignored.** The connection host comes from `noise_host` (provisioned)
   or the hard-coded default. If the server ever moves VMs to other hosts, upstream would break
   too.
9. **Token lifetime is assumed** (~4 h, rotate at 3 h). The access token is checked only via
   `fetch_vms` 401. Refresh never presents the access token, and the `hatch_refresh:` prefix
   must not be doubled.
10. **SDK token semantics.** It is sent to the phone in plaintext JSON *inside* the encrypted
    record (`pairing_confirmed`) and on every refresh. The server records it only at pairing
    time. A token change needs a re-pair. "Gadgets without one will stop pairing". Also read
    the SDK Terms. Using it in a TV app may or may not be in scope of the terms.
11. **Developer mode.** The Muse app needs Settings > Devices > Developer mode to see community
    gadgets.
12. **Side chats 404 until used.** `POST /chat/subscribe` with a new `session_id` returns 404
    until the first `/chat/stream` message to that id creates the side chat. Subscribe with `{}`
    first, then switch to the scoped subscription after the first ack. Replies to the TV's
    questions stream on the side chat's subscription; the ack confirms
    `result.session_id` and `result.is_thread: true`.
13. **Commands: see §3.4.4's `client.invoke` note.** The SDKs' `link.invoke` path never fired
    on this account. The backend may change; the TV handles both.
12. **Wi-Fi provisioning is faked.** The device must already be online. It returns one open
    network and ignores the SSID and password it gets back. `network_ready` and
    `wifi_connected` depend only on reaching `api.muse.ai:443`.
13. **Plaintext error statuses are bare ASCII**, not JSON and not framed (e.g. the bytes
    `error_encryption_required`). Every other device message is JSON and is always chunk-framed,
    even when short.
14. **Strictness the app may rely on.** The counter must be exact (no window). client_finished
    must be the exact object and the first record. After a hello, plaintext other than
    `get_device_info`, `pairing_client_hello` or `pairing_encrypted` is silently ignored, with no
    reply. A new hello at any time restarts the session.
15. **Nonce discipline on the link.** Any reordering between encrypt and socket write breaks the
    session. Encrypt and write under one lock. Any decrypt or protobuf error poisons the
    transport, so you must reconnect.
16. **The `MuseTurn` heuristics are complex and timing-based** (3 s quiet, 30 s follow-up, 1 s
    grace). The reply-binding rules (parent ids, `is_thread`, replay scope) were inferred
    upstream from server behaviour. The SDK CLI itself never consumes replies. Only the helper
    and tests do.
17. **`/chat/subscribe` without a `session_id`** sends `{}`. Presumably that subscribes to the
    main chat or all chats; the code filters by session afterwards.
18. **Event field naming is not formally specified.** The code accepts several aliases
    (`message_id`/`id`, `reply_to_message_id`/`parent_message_id`,
    `display_text`/`transcript`/`content`). Port the fallbacks as-is.
19. **Sizes.** Device→VM message ≤ 256 KB on the control stream. Requests > 64 KiB must be
    streamed. Upstream caps: inbound control message 4 MiB, chat ack 1 MiB, chat event 256 KiB.
20. **Ping/pong.** The SDK relies on WS-level pings every 20 s with a 20 s pong timeout. A
    custom WS client must implement both directions, or idle NAT/edge timeouts will drop the
    session silently.
21. **`error_pairing_unavailable`** is listed as a plaintext status but never emitted by Linux.
    It may be what the ESP32 sends when the window is closed.
22. **Generation and expiry races** are handled carefully upstream (e.g. the deadlock test
    `test_provision_refusal_without_session_keys_does_not_deadlock`). Mirror the lock design:
    one pairing lock, a non-reentrant state lock, and a TX lock. Do not send while holding the
    state lock.
