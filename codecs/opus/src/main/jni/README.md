
# Compile JNI Wrapper for your platform

opus_jni.cpp contain JNI implementation for OpusNative.java interface. 
Please see below compiling instructions for your native platform. 

The JNI implementation for all platform is assuming shared/dynamic libraries, 
so you still have to configure native library path to opus driver itself and to resulting library.

All platforms 

export OPUS_HOME = ...  directory where OPUS library is compiled
export JAVA_HOME = ...  java home directory

## OSX: 

c++ opus_jni.cpp -I $OPUS_HOME/include/ -I $JAVA_HOME/include/ -I $JAVA_HOME/include/darwin/ -lopus -Wall -fPIC -shared -o libopus_jni.dylib

## LINUX

c++ opus_jni.cpp -std=c++11 -I $JAVA_HOME/include -I $JAVA_HOME/linux -I $OPUS_HOME/include -lopus -Wall -fPIC -shared -o libopus_jni.so