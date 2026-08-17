import sys, os, urllib.request

# Argument 1: Native Library Directory (passed from Java)
native_lib_dir = sys.argv[1] if len(sys.argv) > 1 else ""

# scriptdest is the path for a legacy wrapper if needed
scriptdest = "../youtube-dl.sh"
name = "yt-dlp"
url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"

# Clean up old files first
for path in (scriptdest, "youtube-dl", name):
    try:
        if os.path.exists(path): os.unlink(path)
    except:
        pass

print("Downloading '{}' to '{}'...".format(url, name))
try:
    # Use a real browser user-agent for download
    opener = urllib.request.build_opener()
    opener.addheaders = [('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')]
    urllib.request.install_opener(opener)
    
    urllib.request.urlretrieve(url, name)
    print("Download successful.")
except Exception as e:
    print("Download failed: " + str(e))
    sys.exit(1)

# Ensure executable bit is NOT set on the data directory script 
# to avoid SELinux denials on Android 10+. 
# We run it via the native libytdl.so -> libpython.so bridge instead.
try:
    os.chmod(name, 0o600)
except:
    pass

# Create a .sh wrapper as a reference
try:
    with open(scriptdest, "w") as f:
        ytdl_dir = os.getcwd()
        cacert = os.path.abspath(os.path.join(ytdl_dir, "../cacert.pem"))

        f.write("#!/system/bin/sh\n")
        f.write("NATIVE_LIB_DIR=\"{}\"\n".format(native_lib_dir))
        f.write("export PYTHONHOME=\"{}\"\n".format(ytdl_dir))
        f.write("export PYTHONPATH=\"{}/python313.zip\"\n".format(ytdl_dir))
        f.write("export SSL_CERT_FILE=\"{}\"\n".format(cacert))
        f.write("export LD_LIBRARY_PATH=\"$NATIVE_LIB_DIR\"\n")
        f.write("exec \"$NATIVE_LIB_DIR/libytdl.so\" \"$@\"\n")

    os.chmod(scriptdest, 0o700)
    print("Created reference wrapper at {}".format(scriptdest))
except:
    print("Warning: Could not create wrapper")
