#! /bin/bash

for branch do
    echo "==== Switching to $branch ===="
    cd ~/$branch/tomcat8/bin
    echo "==== Starting Tomcat8 ====" 
    ./startup.sh
    echo "==== Stopping Tomcat8 ====" 
    ./shutdown.sh
done
