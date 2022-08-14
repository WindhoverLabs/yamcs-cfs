Cfs Configuration
====================
The cfs plugin needs to know the location of the `registry`. The registry is the place where all configuration of flight
software is stored. At the moment, it is just a yaml file. However in the future, the idea is to make the registry format-agnostic;
such that ground system tools may just fetch any configuration they need at runtime from the registry database.

CfsPlugin-specific configuration, such as the registry, may be loaded via the ```yamcs-cfs``` key as part of your ```yamcs.yaml``` configuration file:

.. code-block:: yaml

    #Configuration tested on YAMCS 5.4.0
    services:
      - class: org.yamcs.http.HttpServer
        args:
          port: 8090

    dataDir: yamcs-data

    instances:
      - yamcs-cfs

    secretKey: changeme

    yamcs-cfs:
      registry: "Displays/Resources/definitions.yaml"

NOTE: At the moment the registry is global. Meaning it is NOT instance-specific. This is something that should be addressed in future
releases.