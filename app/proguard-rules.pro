# The Cast framework instantiates this class by name, read from the
# OPTIONS_PROVIDER_CLASS_NAME manifest meta-data string. R8 can't see that
# reference, so keep the class (and its no-arg constructor) intact.
-keep class app.caster.video.CastOptionsProvider { *; }
