
FFMPEG_LOCATION="/opt/homebrew/Cellar/ffmpeg/5.1.2_5"

CC=clang++
CPPFLAGS= -Wall -fPIC -shared

CPPFLAGS += -I "$$JAVA_HOME/include/"
CPPFLAGS += -I "$$JAVA_HOME/include/darwin/"


# Add ffmpeg libraries
CPPFLAGS += -I $(FFMPEG_LOCATION)/include
CPPFLAGS += -L $(FFMPEG_LOCATION)/lib
CPPFLAGS += -lavcodec
CPPFLAGS += -lswresample
CPPFLAGS += -lavutil


buildlib: ffmpeg_jni.cpp ffmpeg_jni.h 
	$(CC) $(CPPFLAGS) -o libffmpeg_jni.dylib ffmpeg_jni.cpp 
