/**
 * The only study-definition fields Particeps may project into participant-generated UI.
 *
 * Keep this DTO deliberately boring. Researcher-authored consent, notifications, and surveys are
 * rendered through their existing signed-content paths; treatment details never cross this
 * boundary accidentally because this projection has nowhere to put them.
 */
import { trafficShapingEnabled, type CollectorId, type StudyConfiguration } from './types';

/**
 * The App's fixed `traffic_shaping_disclosure` copy, character for character, so the preview shows
 * what participants will read. A test compares it with the App's string resources.
 */
export const PARTICIPANT_VPN_DISCLOSURE = {
  en: 'This study may use a VPN on this device to adjust how quickly apps on this phone transfer data. Traffic stays on your usual network and is not sent through a Particeps server, and Particeps does not record its content or destinations. Particeps may check whether particular apps are installed, but it does not save or upload a list of your installed apps. On Android 17 or later, local-network access is used only to forward local connections that apps start; Particeps does not look for devices on your local network. Another VPN can interrupt this function; if that happens, the study pauses.',
  'zh-TW': '這項研究可能會使用此裝置上的 VPN，調整這支手機上 App 傳輸資料的速度。流量仍使用你原本的網路，不會經由 Particeps 伺服器傳送，Particeps 也不會記錄流量的內容或目的地。Particeps 可能會檢查特定 App 是否已安裝，但不會儲存或上傳你的已安裝 App 清單。Android 17 以上的本機網路權限只用來轉送 App 發起的本機連線；Particeps 不會搜尋你本機網路上的裝置。其他 VPN 可能中斷此功能，屆時研究會暫停。'
} as const;

export interface ParticipantStudyUiModel {
  title: string;
  purpose: string;
  data_category_ids: CollectorId[];
  shows_traffic_disclosure: boolean;
}

export function participantStudyUiModel(configuration: StudyConfiguration): ParticipantStudyUiModel {
  return {
    title: configuration.title,
    purpose: configuration.purpose,
    data_category_ids: configuration.collectors.map((collector) => collector.id),
    shows_traffic_disclosure: trafficShapingEnabled(configuration.traffic_shaping)
  };
}
