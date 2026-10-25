import io.github.ivangsa.gitpublisher.maven.it.ItSupport
ItSupport.assertNothingPushed(basedir)
def log = ItSupport.log(basedir)
assert log.contains('BUILD FAILURE')
// --fail-at-end kept building: the participant after the failing one did check in
assert log.contains('it.reactor:c:1.0-SNAPSHOT is ready')
return true
