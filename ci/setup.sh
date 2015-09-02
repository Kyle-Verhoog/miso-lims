#! /bin/bash

# This script will, given parameters representing the different branches 
# create the necessary Tomcat8 installation directories as well as configure
# the ports accordingly.

cd ~
install="tomcat-install" # default Tomcat install location

if [ -d "$install" ]; then
    echo "==== Tomcat install directory already exists ===="
    echo "==== Skipping download                       ===="
else
    echo "==== Creating Tomcat installation directory  ===="
    mkdir $install
fi

cd $install

tomcat="apache-tomcat-8.0.26" # update to latest Tomcat version

# Fetch tomcat8 (may have to update accordingly
if [ -e "tomcat8" ]; then
    echo "==== Using predownloaded Tomcat8             ===="
else
    echo "==== Downloading Tomcat8                     ===="
    wget http://apache.mirror.iweb.ca/tomcat/tomcat-8/v8.0.26/bin/${tomcat}.tar.gz
    echo "==== Unpacking Tomcat8                       ===="
    tar -zxf ${tomcat}.tar.gz
    mv ${tomcat} tomcat8
fi


defStartPort=8080    # default Tomcat START/connector port
defStopPort=8005     # default Tomcat SHUTDOWN port
defAJPPort=8009      # default Tomcat AJP port
defRedirectPort=8443 # default Tomcat Redirect port

# Variables for the _new_ ports for each Tomcat installation
startPort=8080
stopPort=7080
AJPPort=8009
redirectPort=8443

for branch do 
    mkdir $branch
    cp -r tomcat8 $branch/
    echo "==== Entering ${branch} branch               ===="
    cd $branch/tomcat8/conf
    echo "==== Finding and Replacing Port numbers      ===="
    sed -i -e 's/${defStartPort}/${startPort}/g' server.xml
    sed -i -e 's/${defStopPort}/${stopPort}/g' server.xml
    sed -i -e 's/${defAJPPort}/${AJPPort}/g' server.xml
    sed -i -e 's/${defRedirectPort}/${redirectPort}/g' server.xml
    startPort=$((startPort+1))
    stopPort=$((stopPort+1))
    AJPPort=$((AJPPort+1))
    redirectPort=$((redirectPort+1))
    cd ../../..
done

echo "==== Setup Complete!                             ===="
