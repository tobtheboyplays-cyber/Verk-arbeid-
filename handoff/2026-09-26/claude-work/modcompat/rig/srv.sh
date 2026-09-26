#!/bin/bash
# start the private server copy in background (nice), log to /tmp/hsmc-srv.log
cd /root/hsmc-srv
nohup nice -n 10 java @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.248/unix_args.txt nogui > /tmp/hsmc-srv.log 2>&1 &
echo $! > /root/hsmc-srv.pid; echo "server pid $(cat /root/hsmc-srv.pid)"
for i in $(seq 1 72); do grep -q 'Done (\|Exception in thread\|Crash' /tmp/hsmc-srv.log && break; sleep 5; done
grep -m3 'Done (\|Exception in thread\|Crash\|diagonal' /tmp/hsmc-srv.log | cut -c1-200
