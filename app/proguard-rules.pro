-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep class app.chocola.MainActivity { *; }
-keep class app.chocola.ChocolaApp { *; }
-keep class app.chocola.agent.TaskService { *; }
-keep class app.chocola.data.DiagnosticsProvider { *; }

# Safe YAML parsing uses maps and scalars; Android has no JavaBeans package.
-dontwarn java.beans.**

# Math font descriptions and command definitions refer to these classes and methods by name.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
