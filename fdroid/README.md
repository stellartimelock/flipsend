# F-Droid prep

- `org.stellartimelock.stellarsend.yml` is a draft for the fdroiddata repo. Nothing has been submitted.
- F-Droid builds `:app:assembleSideloadRelease` from `android/` with `-PflipsendCloudUpdate=false`, so the
  app never downloads its own updates from flipsend.app. F-Droid signs the APK with its own key.
- Our own phone builds stay on the existing signing setup. No keystore, password or key is in this repo.
- Store text and images: `fastlane/metadata/android/en-US/`.
- Builds point at the tag `v1.5.63` in https://github.com/stellartimelock/flipsend.
