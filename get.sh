#!/bin/bash

echo "heya, we do some staff here"
ls /proc/sys/net/ipv4/
FILE=/proc/sys/net/ipv4/ip_local_port_range
if test -f "$FILE"; then
    cat $FILE
fi

