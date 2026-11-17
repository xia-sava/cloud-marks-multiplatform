import { getApi } from "./api"
import { handleConnection } from "./connection"

const api = getApi()
api.runtime.onConnect.addListener((port) => handleConnection(api, port))
