Make a symlink `lib` linking to where all the jar files for gumtree are located at. For example:

`lib -> ~/software/gumtree/dist/build/distributions/gumtree-20171114-2.1.0-SNAPSHOT/lib`

To increase memory allocated to JVM, add the following after `sbt` for example:

```
sbt J-Xms256m -J-Xmx2G ...
```
