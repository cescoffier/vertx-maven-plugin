def file = new File(basedir, "build.log")
assert file.exists();
assert file.text.contains("Running in forked mode");
assert file.text.contains("Succeeded in deploying verticle");
assert file.text.contains("Configured HTTP Port is :9090");
assert file.text.contains("Configured Names are :kamesh roland clement jstrachan");