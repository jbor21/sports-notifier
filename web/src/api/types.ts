import type { components } from './schema'

type Schemas = components['schemas']

export type Game = Schemas['GameSummary']
export type GameDetail = Schemas['GameDetail']
export type Follow = Schemas['Follow']
export type FollowKind = Schemas['FollowKind']
export type AlertSetting = Schemas['AlertSetting']
export type AlertType = Schemas['AlertType']
export type AlertPart = Schemas['AlertPart']
export type Notice = Schemas['Notification']
export type User = Schemas['User']
export type Team = Schemas['Team']
export type League = Schemas['League']
