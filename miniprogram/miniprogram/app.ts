import { restoreSession } from './auth/session'

App({ onLaunch() { void restoreSession() } })
