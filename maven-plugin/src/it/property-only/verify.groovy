import io.github.ivangsa.gitpublisher.maven.it.ItSupport
ItSupport.assertPropertyOnlyPublication(basedir, 'snapshots', 'SHA-256', 'property-only')
return true
