1. Overview
2. Implementation details
  - Peercoin
  - Multi-chain
3. Modifications in Scorex
4. Running
  - Configuration
    
    `elm` is root for configuration for this project
    
    There are 4 sections inside it:
    
     - `node` which has basic information about particular node
    
        `app-name` and `name` are strings used to derive name that is given to akka actor system that is created for given node.
        `version` is value that has to be 3 numbers separated with dots like `1.1.1` this value is passed to NetworkController and than used for handshake with other nodes
        `shutdown-hook` is boolean value used to indicate that after application shutdown there should be message logged (in our case).
        `key-pair-seed` is bas58 encoded value that is used to deterministically generate secret for wallet associated with given node
        
        example:
        ```
        node {
            app-name = "elm"
            version = "1.0.0"
            name = "local-node"
            shutdown-hook = true
            key-pair-seed = "5rcRxdD7jwGDe9XgmEodHdgo6681DVtb1wT3DocNchuR"
        }
        ```
        
     - `genesis` which holds configuration for creating genesis block
        `generate` is boolean value that indicates if genesis block should be created for given node, it should be set to `true` only for one node
        
        example:
        ```
        genesis {
            generate = false
            initial-funds = 0
            grains = 10
        }
        ```
    
    
    ```
    elm {
      consensus {
        N = 8
        confirmation-depth = 1
        base-target = 100
      }
    
      forging {
        delay = 10s
        strategy = ["simple-forging-strategy"|"dumb-forging-strategy"]
        simple-forging-strategy {
          target-ratio = 1.0
          min-transactions = 1
          max-transactions = 100
        }
      }
    }
    
    scorex {
      fastHash = "scorex.crypto.hash.Blake256"
      secureHash = "scorex.crypto.hash.ScorexHashChain"
    
      p2p =  {
        bindAddress = 0.0.0.0
        upnp = false
        upnpGatewayTimeout = 7000
        upnpDiscoverTimeout = 3000
        port = 9084
        knownPeers = []
        maxConnections =  10
      }
    
      walletDir = "/tmp/scorex/wallet"
      dataDir = "/tmp/scorex/data"
      rpcPort = 9085
      rpcAllowed = []
      maxRollback = 100
      blockGenerationDelay = 0
      genesisTimestamp = 1460952000000
      apiKeyHash = GmVvcpx1BRUPDZiADbZ7a6zgQV3Sgj2GhNoEiTH9Drdx
      cors = false
    }
    ```
    
5. Testing
6. Conclusions