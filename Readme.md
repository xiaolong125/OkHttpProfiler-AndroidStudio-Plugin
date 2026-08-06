# OkHttpProfiler Android Studio Plugin

### Created by [NerdyThings Channel](https://www.youtube.com/@Nerdy.Things "YouTube.com")

### Developers Eugene Tkachenko && Hanna Tkachenko

The [OkHttp Profiler plugin](https://plugins.jetbrains.com/plugin/11249-okhttp-profiler "OkHttp Profiler") can show requests from OkHttp and Ktor Client directly in the Android Studio tool window.
It supports OkHttp, Retrofit, and Ktor Client on Android.

You can **debug OkHttp request** or response headers, inspect the JSON as a tree, as a plain text etc. And you can easily **create a Java/Kotlin model from the data**. 
Just click the right mouse button on a root element of the tree (or any other), choose Java or Kotlin, and select a folder for a new file in the project.

![Screen2](https://github.com/itkacher/OkHttpProfiler/blob/master/demo.png?raw=true)

---
 
## Installation 

For installation, you need to include the library to your app build.gradle file

    implementation("io.nerdythings:okhttp-profiler:1.1.1")

and add Interceptor to okHttpClient in code
##### For OkHttp
###### Java
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
     if (BuildConfig.DEBUG) {
         builder.addInterceptor(new OkHttpProfilerInterceptor());
     }   
    OkHttpClient client = builder.build(); 

###### Kotlin
    val builder = OkHttpClient.Builder()
    if (BuildConfig.DEBUG) {
        builder.addInterceptor(OkHttpProfilerInterceptor() )
    }    
    val client = builder.build()
    
##### For Retrofit
###### Java
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
     if (BuildConfig.DEBUG) {
         builder.addInterceptor(new OkHttpProfilerInterceptor());
     }   
    OkHttpClient client = builder.build(); 
    Retrofit retrofit = new Retrofit.Builder()
                ......
                .client(client)
                .build();
                
                
###### Kotlin
    val builder = OkHttpClient.Builder()
    if (BuildConfig.DEBUG) {
        builder.addInterceptor( OkHttpProfilerInterceptor() )
    }    
    val client = builder.build()
    val retrofit = Retrofit.Builder()
            ......
            .client(client)
            .build()

##### For Ktor Client

The Ktor integration is provided by the `ktor-client-profiler` module and uses the same Logcat protocol as the existing OkHttp interceptor. Add the module to your Android app, then install the plugin in debug builds:

```kotlin
implementation(project(":ktor-client-profiler"))
```

```kotlin
val client = HttpClient(Android) {
    if (BuildConfig.DEBUG) {
        install(KtorProfiler)
    }
}
```

Authorization, Cookie, and Set-Cookie headers are redacted by default. Add project-specific secrets with:

```kotlin
install(KtorProfiler) {
    sanitizeHeader("X-Api-Key")
    maxBodyLength = 300_000
}
```

Streaming request bodies, server-sent events, and WebSocket payloads are intentionally not buffered. Their request and response metadata still appears in the profiler.

##### For security reasons we recommend enabling OkHttpProfilerInterceptor and KtorProfiler only for DEBUG BUILDS!
Also Proguard will cut it out in the release build.

#### And then enable Android Studio plugin

https://plugins.jetbrains.com/plugin/11249-okhttp-profiler

![Screen2](https://github.com/itkacher/OkHttpProfiler/blob/master/plugin_install1.png?raw=true)

![Screen3](https://github.com/itkacher/OkHttpProfiler/blob/master/plugin_install2.png?raw=true)

![Screen1](https://github.com/itkacher/OkHttpProfiler/blob/master/screen1.png?raw=true)

![Screen4](https://github.com/itkacher/OkHttpProfiler/blob/master/screen2.png?raw=true)

**Have fun!**

## Source Codes 

#### [OkHttp Profiler Plugin Sources](https://github.com/gektor650/OkHttpProfiler-AndroidStudio-Plugin "OkHttp Profiler Plugin Sources").
#### [OkHttp Profiler Android Library](https://github.com/itkacher/OkHttpProfiler "OkHttp Profiler Android Library").



#### Issue with the Android Studio version 4.1 solved in plugin v1.0.13+:
Unfortunately, Google changed realisation of one class but Intellij doesn't. I fixed it, but Intellij IDEA is no longer supported by the OkHttProfiler.
