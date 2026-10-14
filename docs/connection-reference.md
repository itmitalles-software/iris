# Connection and authentication reference

Detailed reference for Apollo's connection options. For the recommended
Tailscale setup see the [README](../README.md#connect-over-tailscale-recommended).

## Authentication modes

Apollo reads `GET /api/status` from your server, detects which kind of login
it needs, and shows only those fields.

> **Security:** Keep Hermes bound to `127.0.0.1` and reach it through
> Tailscale Serve or another authenticated HTTPS tunnel. Plain HTTP/WS is for
> trusted local networks only: a password does not encrypt the connection.

### 1. Run the Hermes backend

The supported setup is `hermes serve` (the headless dashboard API) on
loopback, published over HTTPS by Tailscale Serve. The
[setup script](../README.md#quick-setup-linux-with-systemd) does this for you.
For a manual setup, set these Hermes options (environment variables or the
`dashboard:` section of `~/.hermes/config.yaml`), as listed in the
[README table](../README.md#manual-setup):

```yaml
dashboard:
  public_url: https://<machine>.<tailnet>.ts.net   # the exact address you enter in Apollo
  basic_auth:
    username: <your user name>
    password_hash: scrypt$16384$8$1$<salt>$<key>    # never store a plaintext password
    secret: <32 random bytes, base64>
```

Hermes turns its login gate on for a non-loopback bind or a non-loopback
`public_url`. Without one of them it runs in loopback mode with a local
session token and no password.

### 2. Connect the app

Tap **Auth Login** on the landing screen and enter the server address (for
example `https://<machine>.<tailnet>.ts.net`). Apollo probes the server and
shows the fields it needs:

**Username and password** (login gate on; the recommended setup)

- Enter the username and password you chose for the Hermes login.
- Apollo logs in, stores the session cookie in encrypted storage, and requests
  WebSocket tickets automatically.

**Browser sign-in** (login gate on, OAuth provider such as Nous Portal)

- Apollo opens the system browser for the provider's sign-in and receives the
  result through Hermes' native loopback flow (PKCE).

**Token** (no login gate; only for a loopback address, for example an emulator
through `adb reverse`)

- Apollo reads the session token from the page Hermes serves at `/` and fills
  it in for you. If it cannot read it, enter the token yourself.
- The token changes when Hermes restarts; sign in again after a restart.

### Troubleshooting

- **401 after a correct password:** the login gate is off, so Hermes ignores
  the password and expects its local session token. This happens when
  `public_url` is missing or points to a loopback address. Set `public_url` to
  the exact HTTPS address you use in Apollo and restart Hermes.
- **400 `Invalid Host header`:** Hermes' DNS-rebinding guard rejects the host
  name of the proxied request. Set `public_url` to the HTTPS origin (scheme,
  host and port exactly as entered in Apollo).
- **Certificate prompt or TLS alert on login:** the server or a proxy in front
  of it asks for a client certificate. See
  [mTLS client certificates](#manual-https-client-certificates-mtls) below.
- **Server signed by your own CA:** see
  [user-installed private certificate authorities](#user-installed-private-certificate-authorities).

### Manual HTTPS client certificates (mTLS)

Open **Settings → Connection → HTTPS mTLS client certificate configuration**, or tap
**Connections** on the first-launch landing page before signing in. Install client certificates
in Android settings first; the app stores aliases only, never private keys.

Each binding belongs to an HTTPS hostname and port across all paths and connection
profiles. Use **Add Configuration** or **Edit Configuration**, then **Select Certificate**
or **Change Certificate** to open Android KeyChain. A valid address, port and selected
certificate are required to **Save**. **Delete Configuration** clears the saved binding
without removing the system certificate. Cancelling the picker keeps the draft alias;
leaving an edited draft asks whether to discard unsaved changes. Host/port edits
preserve the draft certificate and invalidate pending choices.
An editor dismissed or destroyed while the picker is open ignores its late callback.

On the login screen, a failed initial `/api/status` probe offers certificate setup only
when its HTTPS error cause chain contains an SSL exception with the exact
received alert `TLSV1_ALERT_CERTIFICATE_REQUIRED`, `SSLV3_ALERT_BAD_CERTIFICATE`,
`SSLV3_ALERT_UNSUPPORTED_CERTIFICATE`, `SSLV3_ALERT_CERTIFICATE_REVOKED`,
`SSLV3_ALERT_CERTIFICATE_EXPIRED`, `SSLV3_ALERT_CERTIFICATE_UNKNOWN`, or
`TLSV1_ALERT_UNKNOWN_CA`. Local server-trust/hostname failures and generic handshake or
network failures do not trigger it. Missing and rejected client certificates use the same
dialog and wording. The dialog fixes the host and port; you must
explicitly select an installed certificate. **Save** verifies that candidate with a fresh
TLS connection and one credential-free `GET /api/status` to that origin. Any HTTP response
headers suffice, including 401/403; redirects are not followed. Failed verification leaves
existing bindings unchanged and allows another selection. Cancel preserves the mTLS
connection error. A successful save replaces any binding for that host and port and clears
the previous connection error. Use the connection button to retry; later errors still
appear normally. The inline notice points to **Connections** for changes.

Peers and providers reporting generic failures or other error formats may not trigger this prompt; use the
manual configuration above. Later login steps, background requests and other screens do
not trigger it. Network requests never open the system picker or wait for selection. Missing,
expired, revoked or incompatible keys result in no client identity, so servers requiring
mTLS fail normally until a usable binding is saved. REST, WebSocket, images, attachments
and audio/video share the TLS configuration. Server certificate and hostname
verification remain enabled; client identity selection does not change server trust.

Saving (including selecting the same alias again) or deleting a configuration retires live
TLS sockets and session contexts for the affected addresses. Retry failed requests or
restart media playback; content already displayed or buffered may remain visible.
Remote image memory/disk cache keys and gateway file cache keys include persistent
identity generations and a shared cache epoch covering redirect destinations, so a late old response cannot populate the new identity's cache.
While any binding exists, remote cache keys also include a per-launch epoch, because a
KeyChain change made while the app was stopped cannot be observed; cached remote content is
reused only within one app launch. Old cache entries age out under the existing cache policies. To isolate client identities,
HTTP/2 connection coalescing across origins is disabled for the shared clients, including
unbound origins; HTTP/2 within one origin remains enabled. A binding change also changes
cache keys for unrelated remote resources because their redirect destinations are unknown
before fetching. Redirects select the destination origin's saved identity only.

### User-installed private certificate authorities

For a server signed by a private CA, install that CA in Android's user certificate
store, then enable **Trust user-installed CAs** in **Connections**, below the mTLS
configuration entry. This global switch defaults to off, including when upgrading
without a saved preference. Off uses the platform-default trust policy; on trusts
active system and user CA roots for all connections using the shared TLS clients.
Only enable it if you trust the installed user CAs. It does not restrict a CA to a
specific server, accept arbitrary certificates, or bypass certificate validity,
chain or hostname checks. mTLS client certificate bindings remain independent.

The switch saves your choice for the next app process start. Restart the app process
(not just the settings page or activity) to apply it. A restart hint appears while
the saved choice differs from this process's active policy; switching back clears
the hint. Existing clients and connections keep the startup policy.

Android loads the CA store and validates certificate chains. The app does not
monitor CA additions or removals or promise that they take effect while running;
restart the app after changing the Android CA store. To stop trusting user CAs,
turn the switch off and restart the app. Remove CAs in Android settings.

### Cloudflare Access and custom headers

1. Enter your dashboard's HTTPS URL on the login screen.
2. Tap **Custom headers**, then **Add Cloudflare headers**.
3. Enter your service token's `CF-Access-Client-Id` and `CF-Access-Client-Secret` values.
4. Tap **Save**. The app probes the dashboard again, then shows the Hermes login fields.

Your [Cloudflare Access policy](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/)
must accept the service token. These headers authenticate with Cloudflare; you still
need to complete Hermes authentication.

**Other headers.** You can add any custom header name and value in the same editor.
To edit or remove saved headers, open **Custom headers** from login, or from the saved
connection's edit dialog in Settings. Values are masked and stored in encrypted
preferences. Saving an empty list removes the headers for that URL.

**Where headers are sent.**

- Profiles with the same server URL share headers.
- They apply to probes, login, API and media requests, and WebSocket handshakes.
- Each URL's scheme, host, port, and path prefix limit where its headers go.
- Redirects outside that scope do not receive them, and WebSocket redirects are not followed.
- The app manages `Authorization`, `Cookie`, and transport headers itself.

### Connection profiles

Have several Hermes servers? Save each one under **Settings → Connection** and
switch between them there. Each saved connection has its own server URL and
login.
