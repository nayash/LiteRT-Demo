package com.outliers.liter_demo

import com.google.ai.edge.litertlm.OpenApiTool

class StockPriceTool : OpenApiTool {
    override fun getToolDescriptionJsonString(): String {
        return """
        {
          "name": "check_stock_price",
          "description": "Get the current stock price for a given ticker symbol",
          "parameters": {
            "type": "object",
            "properties": {
              "ticker": {
                "type": "string",
                "description": "The stock ticker symbol (e.g., AAPL, GOOGL)"
              }
            },
            "required": ["ticker"]
          }
        }
        """.trimIndent()
    }

    override fun execute(paramsJsonString: String): String {
        // This is called in Automatic mode
        return """{"price": "$150.00", "currency": "USD"}"""
    }
}
