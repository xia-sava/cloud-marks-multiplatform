/**
 * 配布版か開発版か。配布版と開発版の拡張は、同じブラウザに並べて入れられるよう、別の拡張として作る
 * （`docs/native-messaging.md` の「配布版と開発版」）。
 */
export type Variant = "distributed" | "dev"

/** 配布版か開発版の拡張の名前と ID。拡張の名前のほかは `contracts/native-messaging/identities.json` の 1 組と同じ。 */
export interface VariantIdentity {
  /** 拡張の名前（manifest の name）。ツールバーのボタンの説明にも使う。 */
  extensionName: string
  /** Native Messaging のホストの名前。 */
  hostName: string
  /** Chrome の拡張の ID。`chromeKey` から決まり、ホストの登録（allowed_origins）が指す。 */
  chromeExtensionId: string
  /** Chrome の拡張の ID を `chromeExtensionId` に固定する公開鍵（manifest の key）。 */
  chromeKey: string
  /** Firefox の拡張の ID（gecko の ID）。ホストの登録（allowed_extensions）が指す。 */
  geckoId: string
  /** 呼び出しの URL のホスト。 */
  callUrlHost: string
}

export const VARIANTS: Record<Variant, VariantIdentity> = {
  distributed: {
    extensionName: "Cloud Marks",
    hostName: "to.sava.cloudmarksmultiplatform",
    chromeExtensionId: "laeogpkfjeccgfkedalbkcbenakkefpl",
    chromeKey:
      "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3ExUFJw8hZbKkln26D2v7f0GjWjKIkPWpfYTognWITCUlTpqYkgo8dqIy2x8wkMSRxSKs9HxdhZgAoxwXCGnTBljYFRfiyH3NcQLbXA1NsnsM1t4RpJN02wyvyURcLBqNHE6im6XGHn0gTcXU+dXe6kZOSQeUcuxRBK+C0ffERhkTRO63+MPaR/iPwgdRcY7Mg2GqdRNQgyAD8ZnoXsDIWjLwc15oZVjNa6NcnbVKyaLJHkdKerY2to7X9MLDGSUHZ4ucOjUzV+OGb4LxAR3mcczus5JXDhenU8zvw618nCizXd7UUpSSEmfI0ZOpUqnnHJHR/G7gi/FMlNThi7JawIDAQAB",
    geckoId: "cloud-marks-multiplatform@sava.to",
    callUrlHost: "cmm.invalid",
  },
  dev: {
    extensionName: "Cloud Marks (dev)",
    hostName: "to.sava.cloudmarksmultiplatform.dev",
    chromeExtensionId: "bnnjmdaaeahhkjopgaeacdpajlfbodca",
    chromeKey:
      "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAiIcf2Qd6BnAKTJy6mxIh0kOlDJJQ20qTcVHyAZrkXj5LNhydEX5fRtl4kstxNeK0+MeVHNQXCijVH4kHCzT0+hA0EhsX8VFf0SjI3xDW/kRHjAEZADtj6+DmTxfm9xhcTRwbqlGkSxDeM/0GxOuw6SXIXHp+mq5gJ7G1ZGwumZVKAP99wJxvOYWbi+F6qN8q4OvhgPtGdS1BR4udtRLFGlOxLcxaQeWdgibmopD5Ide9sXvxJq+5IpHN6ZcNaoJ+T6OVUY7zIWfLcMhrwbNOrrPxxfInLz1XTNIbHx9ZUHSDdMlyp53OkeQWpSjJE9nzsANmdX58XxPnjsCzGBLVnQIDAQAB",
    geckoId: "cloud-marks-multiplatform-dev@sava.to",
    callUrlHost: "cmm-dev.invalid",
  },
}

/**
 * esbuild の define で埋め込む値。配布版の拡張には配布版の、開発版の拡張には開発版の、ホストの名前・呼び出しの URL のホスト・
 * 拡張の名前を埋め込む。キーは `protocol.ts`・`command.ts`・`failure-state.ts` が宣言する名前。
 * ビルドは `scripts/build-extension.ts` が、テストは `vitest.config.ts` が配布版の値で埋め込む。
 * 埋め込まずにビルドした拡張は、読み込んだときに ReferenceError で止まる。
 */
export function variantDefines(variant: Variant): Record<string, string> {
  const identity = VARIANTS[variant]
  return {
    CMM_HOST_NAME: JSON.stringify(identity.hostName),
    CMM_CALL_URL_HOST: JSON.stringify(identity.callUrlHost),
    CMM_EXTENSION_NAME: JSON.stringify(identity.extensionName),
  }
}
