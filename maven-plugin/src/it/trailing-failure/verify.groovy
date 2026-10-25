import io.github.ivangsa.gitpublisher.maven.it.ItSupport
// documented behaviour (D-034): the publication stays although the build failed afterwards
ItSupport.assertOnePublicationOf(basedir, 'a', 'b')
assert ItSupport.log(basedir).contains('BUILD FAILURE')
return true
