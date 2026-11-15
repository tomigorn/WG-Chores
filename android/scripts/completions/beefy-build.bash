_beefy_build_sh() {
    local cur=${COMP_WORDS[COMP_CWORD]} prev=${COMP_WORDS[COMP_CWORD-1]}
    if [ "$prev" = --project ]; then
        mapfile -t COMPREPLY < <(compgen -d -- "$cur")
        return
    fi
    local opts="--emulator --project --head --allow-untracked --write-locks --self-test --dry-run --help"
    local tasks="assembleDebug assembleRelease bundleRelease testDebugUnitTest recordRoborazziDebug verifyRoborazziDebug connectedDebugAndroidTest releaseDeviceCheck connectedCheck test lint clean"
    mapfile -t COMPREPLY < <(compgen -W "$opts $tasks" -- "$cur")
}
complete -F _beefy_build_sh beefy-build.sh
