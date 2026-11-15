_wg_chores() {
    local cur=${COMP_WORDS[COMP_CWORD]} prev=${COMP_WORDS[COMP_CWORD-1]} cmd=${COMP_WORDS[1]}
    local cmds="serve migrate-v1 backup create-user revoke-sessions demo-reset healthcheck"
    COMPREPLY=()
    if [[ $COMP_CWORD -eq 1 ]]; then
        COMPREPLY=($(compgen -W "$cmds -h --help" -- "$cur"))
        return
    fi
    case $prev in
        --from|--password-file) COMPREPLY=($(compgen -f -- "$cur")); return ;;
        --household|--map|--display-name) return ;;
    esac
    case $cmd in
        migrate-v1) COMPREPLY=($(compgen -W "--from --household --map --force -h --help" -- "$cur")) ;;
        backup) COMPREPLY=($(compgen -W "--force -h --help" -- "$cur") $(compgen -f -- "$cur")) ;;
        create-user) COMPREPLY=($(compgen -W "--display-name --password-file -h --help" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "-h --help" -- "$cur")) ;;
    esac
}
complete -F _wg_chores wg-chores
