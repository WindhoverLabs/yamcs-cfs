Yamcs: Cfs Plugin
========================

This is a Yamcs plugin which has a collection of cfs-specific utilities.

More specifically this plugin adds endpoints to the YAMCS HTTP api that clients using cfs flight software
might find useful.

Current utilities include:

* Request the SCH table entries for a specific application
* LinkInfoService provides users with extra information about their current data links(host, port, etc) as system parameters.

These utilities are meant to ease the use of YAMCS with cfs flight software.

.. rubric:: Usage with Maven

At the moment users may obtain the jar files from yamcs-cfs github repository. This package is not on Maven Central at the moment.

.. toctree::
    :titlesonly:
    :caption: Table of Contents

    cfs-config
    http-api/index
    services/link-info-service
