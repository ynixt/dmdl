# Reads aria2's console output.
# Readout lines (starting with "[", or empty) only update the allocation status document and are
# not printed, so the per-second progress readout stays out of the container log.
# Every other line is an aria2 log message (they start with a timestamp) and is printed with an
# "[aria2] " prefix. A future aria2 message starting with "[" would be treated as readout and dropped.
{
    sub(/^\r+/, "")
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
    } else {
        print "[aria2] " $0
        fflush()
    }
}
