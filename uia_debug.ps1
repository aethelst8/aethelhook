Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$btnType = [System.Windows.Automation.ControlType]::Button
$btnCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty, $btnType)

$btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)

Write-Host "=== UIA BUTTONS FOUND: $($btns.Count) ==="
foreach ($b in $btns) {
    $r = $b.Current.BoundingRectangle
    Write-Host "BTN name='$($b.Current.Name)' class='$($b.Current.ClassName)' enabled=$($b.Current.IsEnabled) rect=($($r.Left),$($r.Top))"
}
Write-Host "=== END ==="
