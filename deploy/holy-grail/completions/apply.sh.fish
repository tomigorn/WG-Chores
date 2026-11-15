# source deploy/holy-grail/completions/apply.sh.fish   (fish matches ./apply.sh and deploy/holy-grail/apply.sh by name)
complete -c apply.sh -f
set -l first 'test (count (commandline -opc)) -eq 1'
complete -c apply.sh -n $first --arguments='--dry-run' -d 'Show the diff for ~/Projects, write nothing (default)'
complete -c apply.sh -n $first --arguments='--apply' -d 'Write the files into ~/Projects, no commit'
complete -c apply.sh -n $first --arguments='-h' -d 'Show help'
complete -c apply.sh -n $first --arguments='--help' -d 'Show help'
