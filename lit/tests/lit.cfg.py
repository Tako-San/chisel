import os
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
# Define available features for REQUIRES tags
# Add feature based on actual Scala version being tested
if config.scala_version.startswith("2."):
    config.available_features = ["scala-2"]
elif config.scala_version.startswith("3."):
    config.available_features = ["scala-3"]
else:
    config.available_features = ["scala-2", "scala-3"]
config.test_source_root = os.path.dirname(__file__)
