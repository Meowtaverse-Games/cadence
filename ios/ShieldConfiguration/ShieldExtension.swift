import ManagedSettings
import ManagedSettingsUI
import UIKit
import CadenceCore

final class ShieldExtension: ShieldConfigurationDataSource {
    private func configuration() -> ShieldConfiguration {
        let files = GroupStore(category: "shield")
        let now = Date()
        let timetable = files.timetable()?.1
        let blocked = ActivityPlanner.isBlocked(timetable, at: now)
        let end = blocked ? timetable.flatMap { try? ActivityPlanner.nextTransition($0, now: now) } : nil
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.timeZone = .current
        formatter.dateFormat = "HH:mm"
        return ShieldConfiguration(
            backgroundBlurStyle: .systemMaterialDark,
            backgroundColor: CadencePalette.ground,
            icon: CadenceMark.image(),
            title: .init(text: "集中の時間です", color: CadencePalette.text),
            subtitle: end.map { .init(text: "\(formatter.string(from: $0)) まで", color: CadencePalette.muted) },
            primaryButtonLabel: .init(text: "閉じる", color: CadencePalette.ground),
            primaryButtonBackgroundColor: CadencePalette.focus)
    }

    override func configuration(shielding application: Application) -> ShieldConfiguration { configuration() }
    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration { configuration() }
    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration { configuration() }
    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration { configuration() }
}
