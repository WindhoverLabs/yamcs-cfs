LinkInfoService
===============

Provides users with extra information in system parameters about data links.
Users may configure the service like this:

.. code-block:: python

    services:
      - class: org.yamcs.archive.XtceTmRecorder
      - class: com.windhoverlabs.yamcs.cfs.registry.LinkInfoService
        args:
          links:
            - tc_ppd