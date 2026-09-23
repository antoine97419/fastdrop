import sys

with open("shared/src/commonMain/kotlin/com/fastdrop/transfer/TransferManager.kt", "r") as f:
    text = f.read()

text = text.replace("""        var hashingSink: HashingSink? = null
        var metadata: ControlMessage.FileOffer? = null
        var fileValid = false""", """        var hashingSink: HashingSink? = null
        var metadata: ControlMessage.FileOffer? = null
        var fileValid = false
        var activeDestination: IncomingFileDestination? = null""")

text = text.replace("""            var destination: IncomingFileDestination? = null
            destination = onOfferReceived(offerMsg)
            if (destination == null) {""", """            val destination = onOfferReceived(offerMsg)
            if (destination == null) {""")

text = text.replace("""val rawSink = destination!!.openSink()""", """activeDestination = destination
            val rawSink = destination.openSink()""")


old_finally = """        } finally {
            try { hashingSink?.close() } catch(e: Exception) {}
            if (fileValid) {
                try { onOfferReceived(metadata!!)?.commit() } catch(e: Exception) {} // Wait, we can just save destination reference
            }
        }"""

new_finally = """        } finally {
            try { hashingSink?.close() } catch(e: Exception) {}
            if (fileValid) {
                try { activeDestination?.commit() } catch(e: Exception) {}
            } else {
                try { activeDestination?.abort() } catch(e: Exception) {}
            }
        }"""
text = text.replace(old_finally, new_finally)

with open("shared/src/commonMain/kotlin/com/fastdrop/transfer/TransferManager.kt", "w") as f:
    f.write(text)
