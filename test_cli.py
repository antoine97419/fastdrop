import subprocess
import time
import os

home_a = os.path.abspath(".fastdrop_a")
home_b = os.path.abspath(".fastdrop_b")

file_to_send = os.path.abspath("test_file.txt")
with open(file_to_send, "w") as f:
    f.write("Hello FastDrop from test script! "*100)

os.system("rm -rf .fastdrop_a .fastdrop_b")

print("Run tests manually or use simple Popen...")
