# Eclipse-family fixtures

Answers in the Eclipse-family add-on protocol (SPEC.md 10.2), built from its public documentation: no real hosts and no
tokens. `expect.json` says what a v1 host makes of each, through the shared readers (`stash-addon-types/adapters`):

| Folder | Reader | `expect.json` entry |
|---|---|---|
| `manifest/` | `eclipseManifest` | `value` (the v1 manifest), `viaIsrc`, `allowDownloads`, `quality` per pass (`eclipseQuality`); or `refused` |
| `search/` | `eclipseSearch` | `value`; `rowStream` (`eclipseRowStream` for one row, with the named manifest) |
| `resolve/` | `eclipseResolve` | `asked` (the descriptor) and `value` |
| `stream/` | `eclipseStream` | `value`, or `playable: false`, or `miss: true` |
| `error/` | `eclipseError` | the file is `{ status, headers, body, route }`; `value` is what the host does |

Any host's tests may read them: the checker's and the types package's do.
