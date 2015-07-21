FROM centos:7

RUN curl https://bintray.com/sbt/rpm/rpm > /etc/yum.repos.d/bintray-sbt-rpm.repo
RUN yum -y install git java-1.8.0-openjdk sbt supervisord && yum clean all

EXPOSE 8080

ADD build.sbt /usr/firecloud-orchestration/build.sbt
ADD src /usr/firecloud-orchestration/src
ADD project /usr/firecloud-orchestration/project
ADD application.conf /usr/firecloud-orchestration/application.conf
ADD test.conf /usr/firecloud-orchestration/test.conf

WORKDIR /usr/firecloud-orchestration

RUN sbt assembly -Dconfig.file=/usr/firecloud-orchestration/test.conf

CMD java -Dconfig.file=/usr/firecloud-orchestration/application.conf -jar $(ls target/scala-2.11/FireCloud-Orchestration-assembly-* | tail -n 1)
