# Postman Collection & Environments

The SecureCapita API collection (`../APIs.postman_collection`) uses a single
variable, `{{baseUrl}}`, in place of any hard-coded host:port. The actual URL
is resolved by whichever **environment** is selected in Postman's top-right
dropdown.

## Files

| File                            | Variable `baseUrl`                          | Use when                                  |
| ------------------------------- | ------------------------------------------- | ----------------------------------------- |
| `Local.postman_environment.json`| `http://localhost:8090`                     | running `./deploy.sh` on your laptop      |
| `Dev.postman_environment.json`  | `https://dev.securecapita.example.com`      | hitting the shared dev cluster            |
| `QA.postman_environment.json`   | `https://qa.securecapita.example.com`       | smoke-testing a release candidate         |
| `Stage.postman_environment.json`| `https://stage.securecapita.example.com`    | pre-prod sign-off                         |
| `Prod.postman_environment.json` | `https://app.securecapita.example.com`      | real customer traffic — read-mostly!      |

The non-local URLs are placeholders — edit them to match your actual DNS
once those environments exist.

## Import

In Postman:

1. **File → Import** → drop in `APIs.postman_collection` *and* every
   `*.postman_environment.json` file at once.
2. In the top-right environment dropdown, pick **SecureCapita - Local**
   (or whichever environment you want to point at).
3. Run any request — `{{baseUrl}}/user/login` resolves to
   `http://localhost:8090/user/login` (or the equivalent for the chosen env).

## Adding a new environment

1. Duplicate the closest existing `*.postman_environment.json`.
2. Change `name`, `id`, and the `baseUrl` value.
3. Commit it next to the others.

No collection edits required — the collection only ever references
`{{baseUrl}}`, so a new env file is enough to point it at a new backend.
