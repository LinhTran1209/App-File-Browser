# File Server Android v1.5.0 Design

## Goal

Deliver a native server-settings experience for File Browser v2.63.x, fix the
server delete menu and share dialog sizing, and make name sorting deterministic.

## User Experience

### Server list

- Keep the existing overflow affordance on each server card.
- Render the Delete popup as a compact single-row menu with a normal touch
  target and no oversized empty padding.

### Browser

- Add a Settings action immediately after Transfers in the server app bar.
- When the user toggles name sorting, sort the resources and scroll the active
  list or grid to the first item in the new order.
- Keep the current path, selection and refresh behavior unchanged.

### Share dialog

- Use a bounded rectangular dialog that fits small phones.
- Limit the resource name to two lines.
- Keep duration and duration unit on one row.
- Keep password and actions within the dialog width in both light and dark
  themes.

### Server settings

The screen is native Compose UI and uses four destinations:

1. Profile
   - Hide dotfiles
   - Single-click behavior
   - Redirect after copy/move
   - Exact date format
   - Language
   - Ace editor theme
   - Password change when the server does not lock the password
2. Shares
   - List active share links
   - Copy a share URL
   - Delete a share
3. Global
   - Signup, user-home and login visibility options
   - Minimum password length
   - Global rules
   - Branding options
   - Chunk upload size and retry count
   - Default-user scope, language and permissions
4. Users
   - List users
   - Create, edit and delete users
   - Edit scope, locale, password locking, administrator status, and granular
     resource permissions

All forms are vertically scrollable and theme-aware.

## Permissions

- Profile is available to every authenticated user.
- Shares is available when the authenticated user can share resources; the
  backend remains the authority and may return 403.
- Global and Users are visible only when the authenticated user is an
  administrator.
- Destructive and administrative operations surface backend authorization
  errors instead of assuming the client-side permission check is sufficient.

## Data and API Design

- Extend the existing File Browser transport and session repositories instead
  of creating a second authentication stack.
- Use the existing `X-Auth` token for:
  - `GET/PUT /api/settings`
  - `GET/POST /api/users`
  - `GET/PUT/DELETE /api/users/{id}`
  - `GET /api/shares`
  - `DELETE /api/share/{hash}`
- Parse the authenticated user model returned by the existing session flow so
  navigation and settings tabs can be permission-gated.
- For global settings, retain the complete JSON document received from the
  server and replace only fields represented by the form before PUT. This
  avoids deleting unknown or version-specific backend settings.
- For password-protected user mutations, request the current password only when
  required by the JSON authentication method.

## Error Handling

- Show loading and retry states for every settings destination.
- Keep the previous successful data visible if a refresh fails.
- Show concise API errors for validation, authorization and connectivity
  failures.
- Disable save actions while a mutation is in progress and avoid duplicate
  requests.

## Verification

- Unit-test JSON models, permission gating, global-settings merge behavior,
  share handling and sort-reset behavior.
- Build the debug APK and run the available unit tests and lint/compile checks.
- Do not install the APK; the user will install and perform device acceptance
  testing.

