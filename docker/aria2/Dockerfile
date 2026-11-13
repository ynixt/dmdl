FROM alpine:3.24

RUN apk add --no-cache aria2=1.37.0-r2 coreutils

COPY start-aria2.sh /usr/local/bin/start-aria2
COPY aria2-healthcheck.sh /usr/local/bin/aria2-healthcheck
COPY allocation-readout.awk /usr/local/bin/allocation-readout.awk
RUN chmod 0755 /usr/local/bin/start-aria2 /usr/local/bin/aria2-healthcheck

EXPOSE 6800
ENTRYPOINT ["/usr/local/bin/start-aria2"]
