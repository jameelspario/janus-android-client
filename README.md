## Janus Android Client

### Add it in your root settings.gradle at the end of repositories:


```agsl
dependencyResolutionManagement {
		repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			mavenCentral()
			maven { url = uri("https://jitpack.io") }
		}
}
```

### Add the dependency

```agsl
dependencies {
	        implementation("com.github.jameelspario:janus-android-client:Tag")
}
```