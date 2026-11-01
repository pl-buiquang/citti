# The test APK of the e2eMinified build type follows the build type and would be shrunk too. Only the app
# is under test, so leave the test code as it is (the runner finds the tests and their annotations by reflection).
-dontshrink
-dontobfuscate
-dontoptimize
-dontwarn javax.lang.model.element.Modifier
