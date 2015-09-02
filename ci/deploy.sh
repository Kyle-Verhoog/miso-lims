#! /bin/bash

for branch do
    echo "$branch"
    source ~/$branch/tomcat8/bin/startup.sh
    source ~/$branch/tomcat8/bin/shutdown.sh
done
