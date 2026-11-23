# Running the UI Studio

This imported project is a native Android application. The optional UI Studio
is a separate, dependency-free Python web server; it does not replace the
Android build or modify Android production sources.

- Start the Replit workflow to open the studio at the web preview on port 5000.
- Run `python3 -m unittest discover -s ui_studio/tests -v` for its local tests.
- Run `python3 ui_studio/audit.py` to refresh the root UI audit log and blueprint
  PDF. The studio's Audit tab also has a re-run control.

## Canva connection

The app reads `CANVA_CLIENT_ID` and `CANVA_CLIENT_SECRET` from Replit Secrets.
It never prints or returns their values. In the Canva Developer Portal, enable
the scopes shown in the studio and register the exact callback URL it displays.
The project currently has no production deployment; the displayed development
URL is not a stable production callback. After publishing, set
`CANVA_REDIRECT_URI` to the exact production callback shown by the published
host, then register that URL with Canva.

Tokens are session-bound and stored in `ui_studio/.canva_store/`, which is
Git-ignored and permission-restricted. Canva SVG export is best-effort because
Canva may not offer SVG for every design; manual SVG upload remains available.
The converter rejects unsupported SVG features instead of silently dropping
them. Generated Android XML should be checked with the Android SDK build before
merging it into app resources.

Do not publish this workbench publicly until its access and per-user token
storage have been reviewed for the chosen deployment target.
