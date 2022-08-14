Get Sch Table
===============

Parses the SCH Diag Message and extracts only the entries that belong to the app specified by clients.

.. rubric:: URI Template


.. code-block:: java

    GET /api/{instance}/cfs/sch/table

Request Body:

.. code-block::

    interface GetSchTableRequest{
    instance: string;
    app: string;
    paramPath: string;
    processor: string;
    }


Response Body:

.. code-block::

    interface SchTableResponse {
    schEntry: [SchTableEntry];
    }


Related Types:

.. code-block::

    interface SchTableEntry {

    minor: int64;
    activityNumber: int64;
    messageMacro: string;
    state: EntryState;
    }

    interface enum EntryState {

    ENABLED: string;
    DISABLED: string;
    UNUSED: string;
    }
