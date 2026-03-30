set shell := ["zsh", "-cu"]

default_variant := "GithubDebug"
app_module := "app"

help:
  @echo "Simple Android helpers for ReadYou"
  @echo
  @echo "Commands:"
  @echo "  just test            Run local JVM unit tests for the app"
  @echo "  just connected-test  Run Android instrumentation tests on a connected device/emulator"
  @echo "  just build-apk       Build the APK for the selected variant"
  @echo "  just install-apk     Install the app on a connected device/emulator"
  @echo "  just apk-path        Print the path to the built APK for the selected variant"
  @echo "  just clean           Remove Gradle build outputs"
  @echo
  @echo "Defaults:"
  @echo "  VARIANT={{default_variant}}"
  @echo
  @echo "Examples:"
  @echo "  just test"
  @echo "  just build-apk"
  @echo "  just install-apk"
  @echo "  VARIANT=GithubRelease just build-apk"
  @echo "  VARIANT=FdroidDebug just connected-test"
  @echo
  @echo "Available variants:"
  @echo "  GithubDebug GithubRelease FdroidDebug FdroidRelease GooglePlayDebug GooglePlayRelease"

test:
  ./gradlew ":{{app_module}}:test${VARIANT:-{{default_variant}}}UnitTest"

connected-test:
  ./gradlew ":{{app_module}}:connected${VARIANT:-{{default_variant}}}AndroidTest"

build-apk:
  ./gradlew ":{{app_module}}:assemble${VARIANT:-{{default_variant}}}"

install-apk:
  ./gradlew ":{{app_module}}:install${VARIANT:-{{default_variant}}}"

apk-path:
  @variant="${VARIANT:-{{default_variant}}}"; \
  flavor="$variant"; \
  if [[ "$variant" == *Debug ]]; then \
  build_type="debug"; \
  flavor="${variant%Debug}"; \
  elif [[ "$variant" == *Release ]]; then \
  build_type="release"; \
  flavor="${variant%Release}"; \
  else \
  echo "Unsupported VARIANT=$variant" >&2; \
  exit 1; \
  fi; \
  flavor="$(printf '%s' "$flavor" | awk '{print tolower(substr($0,1,1)) substr($0,2)}')"; \
  find "app/build/outputs/apk/$flavor/$build_type" -maxdepth 1 -type f -name "*.apk" | sort | tail -n 1

clean:
  ./gradlew clean
