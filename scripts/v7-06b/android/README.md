# V7-06B Android QA runner

`run_qa.py` builds the isolated `qa` app and AndroidTest APKs, or installs and runs one
`apply`/`reject` scenario against an explicitly selected device. It requires a fresh
suffix in the `dev.atlas.coach.qa.v706b.*` package namespace. It never installs or targets
`dev.atlas.coach`, and it does not uninstall packages or clear app data.

## Build only

From the repository root, the required compile oracle is:

```sh
python3 scripts/v7-06b/android/run_qa.py build --suffix .qa.v706b.compile
```

This runs the equivalent of:

```sh
cd android
./gradlew :app:assembleQa :app:assembleQaAndroidTest -PqaTestBuildType=qa -PqaSuffix=.qa.v706b.compile --offline
```

No ADB command is used by `build`.

## Run one scenario

Each runtime run gets a unique package suffix automatically. The caller must provide an
explicit ADB serial and the private `stack.json` emitted by the isolated backend stack.
The runner reads `relay_url_android`, `server_id`, `server_public_key`, `pairing_file`,
`core_db`, `core_token_file`, `tool_token_file`, `proxy_trace` and `private_root`. The stack,
pairing and token files must have mode `0600`; token values are read only into memory so
instrumentation output and logcat can be scrubbed before display or storage.
Physical targets are refused unless `ATLAS_ALLOW_PHYSICAL_QA=1` is set for the command.

```sh
python3 scripts/v7-06b/android/run_qa.py run \
  --serial '<explicit-serial>' \
  --action apply \
  --stack-file /path/to/private/stack.json
```

Repeat with `--action reject` for a distinct fresh package. The runner installs without
`-r`, streams the pairing file through `run-as` directly into QA `filesDir` with mode `0600`,
invokes only `V7_06B_CoachLoopInstrumentedTest`, removes the temporary pairing file, and saves
tag-filtered, redacted `ATLAS_V7_06B` logcat under the stack's `private_root`. It does not
start or stop backend, relay, proxy or Core services. It leaves the QA packages installed so
their disposable app data can be inspected or discarded explicitly.
