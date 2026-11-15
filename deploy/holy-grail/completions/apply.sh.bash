# source deploy/holy-grail/completions/apply.sh.bash
_wgc_apply() {
    COMPREPLY=()
    [[ $COMP_CWORD -eq 1 ]] && COMPREPLY=($(compgen -W "--dry-run --apply -h --help" -- "${COMP_WORDS[1]}"))
}
complete -F _wgc_apply apply.sh ./apply.sh deploy/holy-grail/apply.sh ./deploy/holy-grail/apply.sh "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/apply.sh"
