import io.github.ivangsa.gitpublisher.maven.it.ItSupport
assert ItSupport.log(basedir).contains('MultiThreadedBuilder')
ItSupport.assertOnePublicationOf(basedir, 'reactor', 'a', 'b', 'c')
return true
