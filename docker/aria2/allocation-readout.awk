# Read only aria2's numeric console counters. Persist one bounded status document.
{
    print
    fflush()
    if (match($0, /\[FileAlloc:#[0-9a-f]+ [0-9]+B\/[0-9]+B/)) {
        value = substr($0, RSTART, RLENGTH)
        sub(/\[FileAlloc:#/, "", value)
        split(value, fields, " ")
        split(fields[2], sizes, "/")
        gsub(/B/, "", sizes[1])
        gsub(/B/, "", sizes[2])
        printf "{\"gidPrefix\":\"%s\",\"allocatedBytes\":%s,\"totalBytes\":%s}\n", fields[1], sizes[1], sizes[2] > status
        close(status)
    } else if ($0 ~ /^\[/ || $0 == "") {
        print "{}" > status
        close(status)
    }
}
