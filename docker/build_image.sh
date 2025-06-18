#! /bin/bash

docker image rmi kwlee0220/mdt-operation-server

cp ../build/libs/mdt-operation-http-1.0.0.jar mdt-operation-http.jar

docker build -t kwlee0220/mdt-operation-server:latest .

rm mdt-operation-http.jar
