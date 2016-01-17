#######################################################
##  MMS Docker image
##  Note that due to nature of SIP communication
##  The mms is run in host networking space
##  there is a need to specfiy bind address
##  where the server binds to in env
##  - MMS_BINDS_ADDRESS
##
FROM registry.dc01.spinoco.com:5000/spinoco/centos7-jdk

# add media server release
ADD release /opt/mediaserver

# add our configuration
ADD opt /opt

WORKDIR /opt/mediaserver/

# 4g to MS by default. oveerride in deploy if needed
ENV JAVA_OPTS="-Xmx4g -Xms4g"

CMD /opt/mediaserver/bin/run.sh -b $MMS_BINDS_ADDRESS