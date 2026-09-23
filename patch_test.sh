sed -i 's/val data = "hello FastDrop".encodeToByteArray()//g' shared/src/commonTest/kotlin/com/fastdrop/security/SecureChannelTest.kt
sed -i 's/async(Dispatchers.Default) {/val job = async(Dispatchers.Default) {/g' shared/src/commonTest/kotlin/com/fastdrop/security/SecureChannelTest.kt
