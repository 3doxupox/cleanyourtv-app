# Shared settings for the scripts/*-on-forge.sh helpers, which run Docker builds and emulator
# sessions on a remote Linux build host ("forge") over ssh. Sourced, not run.
#
# Nothing machine specific lives in git. Put your values in a local file (sourced when present)
# or export them:
#
#   ${CYT_FORGE_ENV:-$HOME/.config/cleanyourtv/forge.env}
#
#   FORGE        ssh destination of the build host, user@host (required)
#   SSH_KEY      ssh identity for it (default ~/.ssh/id_ed25519)
#   FORGE_ROOT   working directory on the build host (default: "build" in the remote home)
#   CYT_SECRETS  local directory with the signing keys, one subdirectory per key
#                (default ~/.config/cleanyourtv/secrets); never inside this repository
#
# After forge_init: SSH (array), FORGE_ROOT and LOCK (the host build lock every helper takes, so
# only one build or emulator session runs at a time) are set.

CYT_FORGE_ENV="${CYT_FORGE_ENV:-$HOME/.config/cleanyourtv/forge.env}"
# shellcheck disable=SC1090
[ -f "$CYT_FORGE_ENV" ] && . "$CYT_FORGE_ENV"

SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
CYT_SECRETS="${CYT_SECRETS:-$HOME/.config/cleanyourtv/secrets}"

forge_init() {
  if [ -z "${FORGE:-}" ]; then
    echo "FORGE is not set: export FORGE=user@build-host, or set it in $CYT_FORGE_ENV" >&2
    exit 2
  fi
  SSH=(ssh -i "$SSH_KEY" -o ConnectTimeout=10 -o BatchMode=yes "$@" "$FORGE")
  if [ -z "${FORGE_ROOT:-}" ]; then
    FORGE_ROOT="$("${SSH[@]}" 'printf %s "$HOME"' </dev/null)/build"
  fi
  LOCK="$FORGE_ROOT/.cyt-forge.lock"
  "${SSH[@]}" "mkdir -p '$FORGE_ROOT'" </dev/null
}
