import platform
import lit.formats
from lit.llvm import llvm_config
from lit.llvm.subst import ToolSubst

config.name = 'CHISEL'
config.test_format = lit.formats.ShTest(True)
config.suffixes = [".sc"]
config.substitutions = [
    ('%SCALAVERSION', config.scala_version),
    ('%RUNCLASSPATH', ':'.join(config.run_classpath)),
    ('%SCALAPLUGINJARS', ':'.join(config.scala_plugin_jars)),
    ('%JAVAHOME', config.java_home),
    ('%JAVALIBRARYPATH', ':'.join(config.java_library_path))
]
config.test_source_root = os.path.dirname(__file__)

# Add feature detection for Scala 2 vs Scala 3
if config.scala_version.startswith('2.'):
    config.available_features.add('scala-2')
else:
    config.available_features.add('scala-3')
