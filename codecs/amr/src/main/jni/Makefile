CXX=gcc
CPPFLAGS= -Wall -fPIC -shared
CPPFLAGS += -I "$$JAVA_HOME/include/"
OUT_NAME=libffmpeg_jni.so


OS := $(shell uname)
ifeq ($(OS),Darwin)
  # Run MacOS commands
	CPPFLAGS += -I "$$JAVA_HOME/include/darwin/"
	CPPFLAGS += $(shell pkg-config --cflags $(FFMPEG_LIBS))
	CXX := clang++
	OUT_NAME := libffmpeg_jni.dylib
else
  # check for Linux and run other commands
	CPPFLAGS += -I "$$JAVA_HOME/include/linux/"
endif


# Add ffmpeg libraries
FFMPEG_LIBS=libavcodec libswresample libavutil

LIBS = $(shell pkg-config --libs $(FFMPEG_LIBS))

# gcc -shared -Wall -Werror -fpic -o libffmpeg_jni.so ffmpeg_jni.cpp -I "$JAVA_HOME/include/" -I "$JAVA_HOME/include/linux/" -lavcodec -lswresample -lavutil

buildlib: ffmpeg_jni.cpp ffmpeg_jni.h 
	$(CXX) $(CPPFLAGS) -o $(OUT_NAME) ffmpeg_jni.cpp $(LIBS)
