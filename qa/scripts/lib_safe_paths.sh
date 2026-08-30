#!/usr/bin/env bash
# Canonical containment and ownership checks for disposable QA directories.

hsqa_safe_base() {
    local raw="${HSQA_SAFE_TMP_ROOT:-/tmp}" lexical canonical
    case "$raw" in /*) ;; *) echo "FAIL: HSQA_SAFE_TMP_ROOT must be absolute" >&2; return 1 ;; esac
    lexical=$(realpath -ms -- "$raw") || return 1
    canonical=$(realpath -m -- "$raw") || return 1
    [ "$lexical" = "$canonical" ] \
        || { echo "FAIL: safe base may not traverse a symlink: $raw" >&2; return 1; }
    [ -d "$canonical" ] && [ "$canonical" != / ] \
        || { echo "FAIL: unsafe or missing QA safe base: $canonical" >&2; return 1; }
    printf '%s\n' "$canonical"
}

hsqa_safe_target() { # <raw absolute path> <label>
    local raw="$1" label="$2" base lexical canonical
    case "$raw" in /*) ;; *) echo "FAIL: $label path must be absolute" >&2; return 1 ;; esac
    base=$(hsqa_safe_base) || return 1
    lexical=$(realpath -ms -- "$raw") || return 1
    canonical=$(realpath -m -- "$raw") || return 1
    [ "$lexical" = "$canonical" ] \
        || { echo "FAIL: $label path traverses a symlink: $raw" >&2; return 1; }
    [ "$canonical" != / ] && [ "$canonical" != "$base" ] \
        || { echo "FAIL: refusing broad $label target: $canonical" >&2; return 1; }
    case "$canonical" in
        "$base"/*) ;;
        *) echo "FAIL: $label target escapes $base: $canonical" >&2; return 1 ;;
    esac
    printf '%s\n' "$canonical"
}

hsqa_clear_gametest_world() { # <canonical module directory>
    local raw_mod="$1" lexical_mod canonical_mod raw_world lexical_world canonical_world
    case "$raw_mod" in /*) ;; *) echo "FAIL: module path must be absolute" >&2; return 1 ;; esac
    lexical_mod=$(realpath -ms -- "$raw_mod") || return 1
    canonical_mod=$(realpath -m -- "$raw_mod") || return 1
    [ -d "$canonical_mod" ] && [ "$lexical_mod" = "$canonical_mod" ] \
        && [ "$canonical_mod" != / ] \
        || { echo "FAIL: unsafe or symlinked module path: $raw_mod" >&2; return 1; }
    raw_world="$canonical_mod/run/world"
    lexical_world=$(realpath -ms -- "$raw_world") || return 1
    canonical_world=$(realpath -m -- "$raw_world") || return 1
    [ "$lexical_world" = "$raw_world" ] \
        && [ "$canonical_world" = "$raw_world" ] \
        && [ "$canonical_world" != / ] \
        || { echo "FAIL: refusing symlink-escaped GameTest world: $raw_world" >&2; return 1; }
    [ ! -e "$raw_world" ] || rm -rf -- "$raw_world"
    [ ! -e "$raw_world" ] \
        || { echo "FAIL: could not clear exact GameTest world: $raw_world" >&2; return 1; }
}

hsqa_require_plain_mod_run() { # <module directory>; creates only exact run/
    local raw_mod="$1" lexical_mod canonical_mod run lexical_run canonical_run
    case "$raw_mod" in /*) ;; *) return 1;; esac
    lexical_mod=$(realpath -ms -- "$raw_mod") || return 1
    canonical_mod=$(realpath -m -- "$raw_mod") || return 1
    [ -d "$canonical_mod" ] && [ "$lexical_mod" = "$canonical_mod" ] \
        && [ "$canonical_mod" != / ] || return 1
    run="$canonical_mod/run"
    lexical_run=$(realpath -ms -- "$run") || return 1
    canonical_run=$(realpath -m -- "$run") || return 1
    [ "$lexical_run" = "$run" ] && [ "$canonical_run" = "$run" ] \
        || { echo "FAIL: module run directory traverses a symlink: $run" >&2; return 1; }
    if [ ! -e "$run" ]; then
        mkdir -- "$run" || return 1
    fi
    [ -d "$run" ] && [ ! -L "$run" ] || return 1
    printf '%s\n' "$run"
}

hsqa_require_plain_mod_run_file() { # <module> <safe basename>
    local module="$1" basename="$2" run target lexical canonical
    [[ "$basename" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}$ ]] || return 1
    run=$(hsqa_require_plain_mod_run "$module") || return 1
    target="$run/$basename"
    lexical=$(realpath -ms -- "$target") || return 1
    canonical=$(realpath -m -- "$target") || return 1
    [ "$lexical" = "$target" ] && [ "$canonical" = "$target" ] \
        && { [ ! -e "$target" ] || { [ -f "$target" ] && [ ! -L "$target" ]; }; } \
        || { echo "FAIL: module run file is symlinked/non-regular: $target" >&2; return 1; }
    printf '%s\n' "$target"
}

hsqa_require_plain_mod_log() { # <module>; returns exact run/logs/latest.log
    local module="$1" run logs lexical canonical target
    run=$(hsqa_require_plain_mod_run "$module") || return 1
    logs="$run/logs"
    lexical=$(realpath -ms -- "$logs") || return 1
    canonical=$(realpath -m -- "$logs") || return 1
    [ "$lexical" = "$logs" ] && [ "$canonical" = "$logs" ] \
        || { echo "FAIL: module log directory traverses a symlink: $logs" >&2; return 1; }
    if [ ! -e "$logs" ]; then mkdir -- "$logs" || return 1; fi
    [ -d "$logs" ] && [ ! -L "$logs" ] || return 1
    target="$logs/latest.log"
    lexical=$(realpath -ms -- "$target") || return 1
    canonical=$(realpath -m -- "$target") || return 1
    [ "$lexical" = "$target" ] && [ "$canonical" = "$target" ] \
        && { [ ! -e "$target" ] || { [ -f "$target" ] && [ ! -L "$target" ]; }; } \
        || { echo "FAIL: module latest.log is symlinked/non-regular: $target" >&2; return 1; }
    printf '%s\n' "$target"
}

hsqa_validate_marker_contract() { # <marker filename> <exact marker text>
    local marker="$1" expected="$2"
    local marker_pattern='^\.hsqa-[a-z0-9][a-z0-9_-]{0,63}$'
    local expected_pattern='^hsqa-[a-z0-9][a-z0-9:_-]{0,95}$'
    [[ "$marker" =~ $marker_pattern ]] \
        && [[ "$expected" =~ $expected_pattern ]]
}

hsqa_require_owned_directory() { # <directory> <marker filename> <exact marker text>
    local directory="$1" marker="$2" expected="$3" current_uid
    hsqa_validate_marker_contract "$marker" "$expected" \
        || { echo "FAIL: invalid QA ownership marker contract" >&2; return 1; }
    current_uid=$(id -u) || return 1
    [ -d "$directory" ] && [ ! -L "$directory" ] \
        || { echo "FAIL: owned QA directory is missing or symlinked: $directory" >&2; return 1; }
    [ "$(stat -c %u -- "$directory" 2>/dev/null)" = "$current_uid" ] \
        || { echo "FAIL: QA directory is not owned by current uid: $directory" >&2; return 1; }
    [ -f "$directory/$marker" ] && [ ! -L "$directory/$marker" ] \
        || { echo "FAIL: refusing unowned QA directory: $directory" >&2; return 1; }
    [ "$(stat -c %u -- "$directory/$marker" 2>/dev/null)" = "$current_uid" ] \
        && [ "$(stat -c %h -- "$directory/$marker" 2>/dev/null)" = 1 ] \
        || { echo "FAIL: unsafe QA ownership marker identity in $directory" >&2; return 1; }
    cmp -s -- "$directory/$marker" <(printf '%s\n' "$expected") \
        || { echo "FAIL: wrong ownership marker in $directory" >&2; return 1; }
}

hsqa_claim_empty_directory() { # <directory> <marker filename> <exact marker text>
    local directory="$1" marker="$2" expected="$3"
    hsqa_validate_marker_contract "$marker" "$expected" \
        || { echo "FAIL: invalid QA ownership marker contract" >&2; return 1; }
    if [ ! -e "$directory" ]; then
        mkdir -p -- "$directory" || return 1
    fi
    [ -d "$directory" ] && [ ! -L "$directory" ] \
        || { echo "FAIL: QA target is not a plain directory: $directory" >&2; return 1; }
    if [ -e "$directory/$marker" ]; then
        hsqa_require_owned_directory "$directory" "$marker" "$expected"
        return
    fi
    [ -z "$(find "$directory" -mindepth 1 -maxdepth 1 -print -quit)" ] \
        || { echo "FAIL: refusing to claim non-empty QA directory: $directory" >&2; return 1; }
    printf '%s\n' "$expected" > "$directory/$marker"
    hsqa_require_owned_directory "$directory" "$marker" "$expected"
}

hsqa_validate_uint() { # <value> <minimum> <maximum>; overflow-safe decimal
    local value="$1" minimum="$2" maximum="$3"
    local value_length minimum_length maximum_length LC_ALL=C
    [[ "$value" =~ ^(0|[1-9][0-9]*)$ ]] \
        && [[ "$minimum" =~ ^(0|[1-9][0-9]*)$ ]] \
        && [[ "$maximum" =~ ^(0|[1-9][0-9]*)$ ]] \
        || return 1
    value_length=${#value}
    minimum_length=${#minimum}
    maximum_length=${#maximum}
    [ "$value_length" -gt "$minimum_length" ] \
        || { [ "$value_length" -eq "$minimum_length" ] \
            && ! [[ "$value" < "$minimum" ]]; } \
        || return 1
    [ "$value_length" -lt "$maximum_length" ] \
        || { [ "$value_length" -eq "$maximum_length" ] \
            && ! [[ "$maximum" < "$value" ]]; }
}

hsqa_validate_port() { # <value>
    hsqa_validate_uint "$1" 1024 65535
}

hsqa_validate_role() { # <value>
    [[ "$1" =~ ^[a-z0-9][a-z0-9_-]{0,31}$ ]]
}

hsqa_validate_level_type() { # <value>
    case "$1" in
        flat|normal) return 0 ;;
        *) return 1 ;;
    esac
}
