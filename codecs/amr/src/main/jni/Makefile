CXX=c++
CPPFLAGS= -Wall -fPIC -shared
CPPFLAGS += -I "$$JAVA_HOME/include/"


OS := $(shell uname)
ifeq ($(OS),Darwin)
  # Run MacOS commands
	CPPFLAGS += -I "$$JAVA_HOME/include/darwin/"
	CXX := clang++
else
  # check for Linux and run other commands
	CPPFLAGS += -I "$$JAVA_HOME/include/linux/"
endif


# Add ffmpeg libraries
FFMPEG_LIBS=libavcodec libavutil libswresample

CPPFLAGS += $(shell pkg-config --cflags $(FFMPEG_LIBS))
CPPFLAGS += $(shell pkg-config --libs $(FFMPEG_LIBS))


buildlib: ffmpeg_jni.cpp ffmpeg_jni.h 
	$(CXX) $(CPPFLAGS) -o libffmpeg_jni.dylib ffmpeg_jni.cpp 
